package com.ai.walmartads.wfs;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

@Component
public class WfsReportParser {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.US),
            DateTimeFormatter.ofPattern("M/d/yyyy h:mm a", Locale.US)
    );
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
    );

    public ParsedReport parse(WfsReportType type, String fileName, byte[] content) throws IOException {
        List<List<String>> matrix = readMatrix(fileName, content);
        if (matrix.isEmpty()) {
            throw new IllegalArgumentException("文件没有可读取的内容");
        }
        return type == WfsReportType.STORAGE ? parseStorage(matrix) : parseSettlement(matrix);
    }

    private ParsedReport parseStorage(List<List<String>> matrix) {
        int headerIndex = findRow(matrix, row -> "gtin".equals(norm(cell(row, 0))));
        if (headerIndex < 0) throw new IllegalArgumentException("Storage 文件缺少 GTIN 明细表头");
        int summaryIndex = findRow(matrix.subList(0, headerIndex), row -> "report start date".equals(norm(cell(row, 0))));
        if (summaryIndex < 0 || summaryIndex + 1 >= matrix.size()) {
            throw new IllegalArgumentException("Storage 文件缺少 Report start date / Report end date 汇总区");
        }
        List<String> summaryHeaders = matrix.get(summaryIndex);
        List<String> summaryValues = matrix.get(summaryIndex + 1);
        Map<String, String> summary = rawMap(summaryHeaders, summaryValues);
        LocalDate periodStart = requiredDate(value(summary, "report start date"), "Report start date");
        LocalDate periodEnd = requiredDate(value(summary, "report end date"), "Report end date");
        List<String> headers = matrix.get(headerIndex);
        Map<String, Integer> columns = columnMap(headers);
        requireColumns(columns, "gtin", "sku", "item id", "item name", "days in report period", "final storage fee");

        List<ParsedRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int index = headerIndex + 1; index < matrix.size(); index++) {
            List<String> source = matrix.get(index);
            if (isEmpty(source) || isStorageDescription(source)) continue;
            try {
                Map<String, String> raw = rawMap(headers, source);
                String sku = requiredText(value(raw, "sku"), "SKU");
                String gtin = requiredText(value(raw, "gtin"), "GTIN");
                StorageRow row = new StorageRow(
                        periodStart,
                        periodEnd,
                        gtin,
                        sku,
                        text(value(raw, "item id")),
                        text(value(raw, "item name")),
                        decimal(value(raw, "length (in)"), false, "Length (in)"),
                        decimal(value(raw, "width (in)"), false, "Width (in)"),
                        decimal(value(raw, "height (in)"), false, "Height (in)"),
                        decimal(value(raw, "weight (lb)"), false, "Weight (lb)"),
                        storageMetric(raw, "standard", "daily storage fee per unit"),
                        storageMetric(raw, "peak", "daily storage fee per unit"),
                        storageLongTermMetric(raw, false, "daily storage fee per unit"),
                        storageLongTermMetric(raw, true, "daily storage fee per unit"),
                        storageMetric(raw, "standard", "average", "units available"),
                        storageMetric(raw, "peak", "average", "units available"),
                        storageLongTermMetric(raw, false, "average", "units available"),
                        storageLongTermMetric(raw, true, "average", "units available"),
                        integer(value(raw, "days in report period"), "Days in report period"),
                        decimal(value(raw, "final storage fee"), true, "Final storage fee"),
                        decimal(value(raw, "original amount"), false, "Original Amount"),
                        decimal(value(raw, "discount savings"), false, "Discount Savings"),
                        raw
                );
                rows.add(row);
            } catch (RuntimeException exception) {
                errors.add(new RowError(index + 1, exception.getMessage()));
            }
        }
        if (rows.isEmpty() && errors.isEmpty()) throw new IllegalArgumentException("Storage 文件没有明细数据");
        return new ParsedReport(
                WfsReportType.STORAGE,
                sourceStore(matrix, headerIndex),
                periodStart,
                periodEnd,
                normalizeSummary(summary),
                rows,
                errors
        );
    }

    private ParsedReport parseSettlement(List<List<String>> matrix) {
        int headerIndex = findRow(matrix, row -> norm(cell(row, 0)).startsWith("walmart.com order")
                && row.stream().map(WfsReportParser::norm).anyMatch("transaction type"::equals));
        if (headerIndex < 0) throw new IllegalArgumentException("Settlement 文件缺少 Walmart.com Order 明细表头");
        int periodIndex = findRow(matrix.subList(0, headerIndex), row -> norm(cell(row, 0)).startsWith("reportingperiod_startdate"));
        if (periodIndex < 0 || periodIndex + 1 >= matrix.size()) {
            throw new IllegalArgumentException("Settlement 文件缺少 ReportingPeriod_StartDate / EndDate");
        }
        List<String> periodHeaders = matrix.get(periodIndex);
        List<String> periodValues = matrix.get(periodIndex + 1);
        Map<String, String> summary = rawMap(periodHeaders, periodValues);
        LocalDate periodStart = requiredDate(value(summary, "reportingperiod_startdate"), "ReportingPeriod_StartDate");
        LocalDate periodEnd = requiredDate(value(summary, "reportingperiod_enddate"), "ReportingPeriod_EndDate");
        List<String> headers = matrix.get(headerIndex);
        Map<String, Integer> columns = columnMap(headers);
        requireColumns(columns, "transaction type", "wfsreferenceid", "partner gtin", "net payable");

        List<ParsedRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int index = headerIndex + 1; index < matrix.size(); index++) {
            List<String> source = matrix.get(index);
            if (isEmpty(source)) continue;
            try {
                Map<String, String> raw = rawMap(headers, source);
                String transactionType = requiredText(value(raw, "transaction type"), "Transaction Type");
                LocalDateTime txnAt = dateTime(value(raw, "transaction date/time"), false, "Transaction Date/Time");
                SettlementRow row = new SettlementRow(
                        periodStart,
                        periodEnd,
                        text(value(raw, "walmart.com order #")),
                        text(value(raw, "walmart.com order line #")),
                        text(value(raw, "walmart.com po #")),
                        text(value(raw, "walmart.com po line #")),
                        transactionType,
                        text(value(raw, "reason code")),
                        text(value(raw, "detail")),
                        text(value(raw, "wfsreferenceid")),
                        txnAt,
                        decimal(value(raw, "qty"), false, "Qty"),
                        text(value(raw, "billing method")),
                        text(value(raw, "partner id")),
                        text(value(raw, "partner gtin")),
                        text(value(raw, "partner item name")),
                        text(value(raw, "category")),
                        text(value(raw, "sub category")),
                        decimal(value(raw, "net payable"), true, "Net Payable"),
                        text(value(raw, "customerorderid")),
                        text(value(raw, "orderchannelid")),
                        decimal(value(raw, "original amount"), false, "Original Amount"),
                        decimal(value(raw, "discount savings"), false, "Discount Savings"),
                        raw
                );
                rows.add(row);
            } catch (RuntimeException exception) {
                errors.add(new RowError(index + 1, exception.getMessage()));
            }
        }
        if (rows.isEmpty() && errors.isEmpty()) throw new IllegalArgumentException("Settlement 文件没有明细数据");
        return new ParsedReport(
                WfsReportType.SETTLEMENT,
                sourceStore(matrix, headerIndex),
                periodStart,
                periodEnd,
                normalizeSummary(summary),
                rows,
                errors
        );
    }

    private List<List<String>> readMatrix(String fileName, byte[] content) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return readCsv(content);
        if (lower.endsWith(".xlsx")) return readWorkbook(content);
        throw new IllegalArgumentException("仅支持 .csv 或 .xlsx 文件");
    }

    private List<List<String>> readCsv(byte[] content) throws IOException {
        String source = new String(content, StandardCharsets.UTF_8);
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        List<List<String>> matrix = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(false)
                .build()
                .parse(new StringReader(source))) {
            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(record.size());
                record.forEach(value -> row.add(cleanCell(value)));
                matrix.add(row);
            }
        }
        return matrix;
    }

    private List<List<String>> readWorkbook(byte[] content) throws IOException {
        List<List<String>> matrix = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.US);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    matrix.add(List.of());
                    continue;
                }
                int width = Math.max(0, row.getLastCellNum());
                List<String> values = new ArrayList<>(width);
                for (int column = 0; column < width; column++) {
                    values.add(cleanCell(formatter.formatCellValue(row.getCell(column), evaluator)));
                }
                matrix.add(values);
            }
        }
        return matrix;
    }

    private static Map<String, String> rawMap(List<String> headers, List<String> row) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = cleanCell(headers.get(i));
            if (header.isBlank()) continue;
            result.put(header, cleanCell(cell(row, i)));
        }
        return result;
    }

    private static Map<String, Integer> columnMap(List<String> headers) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String key = norm(headers.get(i));
            if (!key.isBlank()) result.putIfAbsent(key, i);
        }
        return result;
    }

    private static void requireColumns(Map<String, Integer> columns, String... required) {
        for (String column : required) {
            if (!columns.containsKey(column)) throw new IllegalArgumentException("文件缺少字段：" + column);
        }
    }

    private static int findRow(List<List<String>> matrix, Predicate<List<String>> predicate) {
        for (int i = 0; i < Math.min(matrix.size(), 40); i++) {
            if (predicate.test(matrix.get(i))) return i;
        }
        return -1;
    }

    private static String sourceStore(List<List<String>> matrix, int beforeIndex) {
        for (int i = 0; i < Math.min(beforeIndex, matrix.size()); i++) {
            String value = text(cell(matrix.get(i), 0));
            if (value != null) return value;
        }
        return null;
    }

    private static boolean isStorageDescription(List<String> row) {
        String first = norm(cell(row, 0));
        String last = norm(cell(row, row.size() - 1));
        return first.startsWith("14-digit global trade item number") || last.startsWith("to find the final storage fee");
    }

    private static boolean isEmpty(List<String> row) {
        return row == null || row.stream().allMatch(value -> cleanCell(value).isBlank());
    }

    private static String cell(List<String> row, int index) {
        return row != null && index >= 0 && index < row.size() ? row.get(index) : "";
    }

    private static String value(Map<String, String> raw, String normalizedHeader) {
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (norm(entry.getKey()).equals(normalizedHeader)) return entry.getValue();
        }
        return null;
    }

    private static BigDecimal storageMetric(Map<String, String> raw, String prefix, String... requiredParts) {
        return metric(raw, name -> name.startsWith(prefix) && containsAll(name, requiredParts) && !name.contains("long-term"));
    }

    private static BigDecimal storageLongTermMetric(Map<String, String> raw, boolean olderThan450, String... requiredParts) {
        return metric(raw, name -> name.startsWith("long-term")
                && containsAll(name, requiredParts)
                && (olderThan450 == (name.contains("450+") || name.contains("451+"))));
    }

    private static BigDecimal metric(Map<String, String> raw, Predicate<String> predicate) {
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (predicate.test(norm(entry.getKey()))) return decimal(entry.getValue(), false, entry.getKey());
        }
        return null;
    }

    private static boolean containsAll(String value, String... parts) {
        for (String part : parts) if (!value.contains(part)) return false;
        return true;
    }

    private static Map<String, Object> normalizeSummary(Map<String, String> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = norm(key);
            if (normalized.contains("fee") || normalized.contains("amount") || normalized.contains("savings")) {
                try {
                    result.put(key, decimal(value, false, key));
                } catch (RuntimeException ignored) {
                    result.put(key, text(value));
                }
            } else if (normalized.contains("date")) {
                LocalDate parsed = date(value);
                result.put(key, parsed == null ? text(value) : parsed);
            } else {
                result.put(key, text(value));
            }
        });
        return result;
    }

    private static String cleanCell(String value) {
        if (value == null) return "";
        String clean = value.replace("\uFEFF", "").strip();
        if (clean.length() >= 3 && clean.startsWith("=\"") && clean.endsWith("\"")) {
            clean = clean.substring(2, clean.length() - 1).replace("\"\"", "\"");
        }
        return clean;
    }

    private static String norm(String value) {
        return cleanCell(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String text(String value) {
        String clean = cleanCell(value);
        return clean.isBlank() ? null : clean;
    }

    private static String requiredText(String value, String label) {
        String clean = text(value);
        if (clean == null) throw new IllegalArgumentException(label + " 不能为空");
        return clean;
    }

    private static BigDecimal decimal(String value, boolean required, String label) {
        String clean = cleanCell(value).replace(",", "").replace("$", "");
        boolean negative = clean.startsWith("(") && clean.endsWith(")");
        if (negative) clean = clean.substring(1, clean.length() - 1);
        if (clean.isBlank() || "-".equals(clean)) {
            if (required) throw new IllegalArgumentException(label + " 不能为空");
            return null;
        }
        try {
            BigDecimal number = new BigDecimal(clean);
            return negative ? number.negate() : number;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " 不是有效金额或数字：" + value);
        }
    }

    private static Integer integer(String value, String label) {
        BigDecimal number = decimal(value, true, label);
        try {
            return number.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " 必须是整数：" + value);
        }
    }

    private static LocalDate requiredDate(String value, String label) {
        LocalDate parsed = date(value);
        if (parsed == null) throw new IllegalArgumentException(label + " 不是有效日期：" + value);
        return parsed;
    }

    private static LocalDate date(String value) {
        LocalDateTime dateTime = dateTime(value, false, "日期");
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    private static LocalDateTime dateTime(String value, boolean required, String label) {
        String clean = cleanCell(value);
        if (clean.isBlank()) {
            if (required) throw new IllegalArgumentException(label + " 不能为空");
            return null;
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(clean, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(clean, formatter).atStartOfDay();
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            double serial = Double.parseDouble(clean);
            if (DateUtil.isValidExcelDate(serial)) {
                return LocalDateTime.ofInstant(DateUtil.getJavaDate(serial).toInstant(), ZoneId.systemDefault());
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(label + " 不是有效日期：" + value);
    }

    public record ParsedReport(
            WfsReportType type,
            String sourceStore,
            LocalDate periodStart,
            LocalDate periodEnd,
            Map<String, Object> summary,
            List<ParsedRow> rows,
            List<RowError> errors
    ) {
        public int totalRows() {
            return rows.size() + errors.size();
        }
    }

    public sealed interface ParsedRow permits StorageRow, SettlementRow {
        Map<String, String> rawFields();
    }

    public record StorageRow(
            LocalDate periodStart,
            LocalDate periodEnd,
            String gtin,
            String sku,
            String itemId,
            String itemName,
            BigDecimal lengthIn,
            BigDecimal widthIn,
            BigDecimal heightIn,
            BigDecimal weightLb,
            BigDecimal standardDailyFee,
            BigDecimal peakDailyFee,
            BigDecimal longTermDailyFee,
            BigDecimal longTermOver450DailyFee,
            BigDecimal standardAverageUnits,
            BigDecimal peakAverageUnits,
            BigDecimal longTermAverageUnits,
            BigDecimal longTermOver450AverageUnits,
            Integer daysInPeriod,
            BigDecimal finalStorageFee,
            BigDecimal originalAmount,
            BigDecimal discountSavings,
            Map<String, String> rawFields
    ) implements ParsedRow {
    }

    public record SettlementRow(
            LocalDate periodStart,
            LocalDate periodEnd,
            String orderId,
            String orderLine,
            String poNumber,
            String poLine,
            String transactionType,
            String reasonCode,
            String detail,
            String wfsReferenceId,
            LocalDateTime transactionAt,
            BigDecimal quantity,
            String billingMethod,
            String partnerId,
            String partnerGtin,
            String partnerItemName,
            String category,
            String subCategory,
            BigDecimal netPayable,
            String customerOrderId,
            String orderChannelId,
            BigDecimal originalAmount,
            BigDecimal discountSavings,
            Map<String, String> rawFields
    ) implements ParsedRow {
    }

    public record RowError(int rowNumber, String message) {
    }
}
