package com.ai.walmartads.importing.parser;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.OrderRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HistoricalWorkbookParser {

    private static final String ORDER_SHEET = "当天销量";
    private static final String AD_SHEET = "当天广告数据";
    private static final Set<String> PRIVATE_FIELDS = Set.of(
            "Customer Name",
            "Customer Shipping Address",
            "Customer Phone Number",
            "Ship to Address 1",
            "Ship to Address 2",
            "City",
            "State",
            "Zip"
    );

    private final JsonPayloadWriter json;
    private final WalmartAdsProperties properties;

    public HistoricalWorkbookParser(JsonPayloadWriter json, WalmartAdsProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    public HistoricalWorkbookRows parse(Path path, ImportFile importFile) throws IOException {
        LocalDate endDateExclusive = properties.historicalEndDateExclusive();
        if (endDateExclusive == null) {
            throw new IllegalArgumentException("历史数据导入缺少截止日期配置");
        }
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            ParsedRows<OrderRecord> orders = parseOrders(
                    requiredSheet(workbook, ORDER_SHEET),
                    importFile,
                    endDateExclusive
            );
            ParsedRows<AdPerformance> ads = parseAds(
                    requiredSheet(workbook, AD_SHEET),
                    importFile,
                    endDateExclusive
            );
            return new HistoricalWorkbookRows(
                    orders.sourceRowCount() + ads.sourceRowCount(),
                    orders.rows(),
                    ads.rows()
            );
        }
    }

    private ParsedRows<OrderRecord> parseOrders(
            Sheet sheet,
            ImportFile importFile,
            LocalDate endDateExclusive
    ) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        Map<String, Integer> headers = headers(headerRow);
        require(headers, "Order Date", "Order#", "SKU", "Qty", "Shipping Cost", "Tax");

        List<OrderRecord> records = new ArrayList<>();
        int sourceRows = 0;
        for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            sourceRows++;
            LocalDate orderDate = cellDate(row.getCell(headers.get("Order Date")));
            if (orderDate == null || !orderDate.isBefore(endDateExclusive)) {
                continue;
            }
            String sku = value(row, headers, "SKU");
            String orderNumber = value(row, headers, "Order#");
            if (sku == null || orderNumber == null) {
                continue;
            }

            Map<String, Object> raw = rawPayload(row, headerRow, PRIVATE_FIELDS);
            String itemCost = value(row, headers, "Shipping Cost");
            String actualShippingCost = value(row, headers, "Tax");
            raw.put("_normalizedItemCost", itemCost);
            raw.put("_normalizedShippingCost", actualShippingCost);

            records.add(new OrderRecord(
                    importFile,
                    orderDate,
                    orderDate,
                    value(row, headers, "PO#"),
                    orderNumber,
                    value(row, headers, "Line#"),
                    sku,
                    ImportValues.parentSku(sku),
                    value(row, headers, "UPC"),
                    value(row, headers, "Status"),
                    ImportValues.decimal(value(row, headers, "Qty")),
                    ImportValues.decimal(itemCost),
                    ImportValues.decimal(itemCost),
                    ImportValues.decimal(actualShippingCost),
                    value(row, headers, "Item Description"),
                    value(row, headers, "Shipping Method"),
                    value(row, headers, "Fulfillment Entity"),
                    json.write(raw)
            ));
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private ParsedRows<AdPerformance> parseAds(
            Sheet sheet,
            ImportFile importFile,
            LocalDate endDateExclusive
    ) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        requireColumn(headerRow, 0, "广告活动名称");
        requireColumn(headerRow, 4, "产品ID");
        requireColumn(headerRow, 8, "展示次数");
        requireColumn(headerRow, 11, "广告支出");
        requireColumn(headerRow, 32, "日期");

        List<AdPerformance> records = new ArrayList<>();
        int sourceRows = 0;
        for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            sourceRows++;
            LocalDate reportDate = cellDate(row.getCell(32));
            if (reportDate == null || !reportDate.isBefore(endDateExclusive)) {
                continue;
            }
            String campaignName = text(row.getCell(0));
            if (campaignName == null) {
                continue;
            }

            String parentSku = firstNonBlank(
                    text(row.getCell(34)),
                    text(row.getCell(33)),
                    ImportValues.parentSku(campaignName)
            );
            BigDecimal spend = decimal(row.getCell(11));
            BigDecimal sales = decimal(row.getCell(15));
            BigDecimal roas = decimal(row.getCell(30));
            if (roas.signum() == 0 && spend.signum() > 0) {
                roas = sales.divide(spend, 8, RoundingMode.HALF_UP);
            }

            records.add(new AdPerformance(
                    importFile,
                    reportDate,
                    ImportFileType.AD_AUTO,
                    campaignName,
                    text(row.getCell(1)),
                    "Sponsored Products (Auto)",
                    null,
                    BigDecimal.ZERO,
                    text(row.getCell(2)),
                    text(row.getCell(3)),
                    text(row.getCell(4)),
                    null,
                    parentSku,
                    text(row.getCell(5)),
                    null,
                    null,
                    null,
                    decimal(row.getCell(6)),
                    decimal(row.getCell(7)),
                    whole(row.getCell(8)),
                    whole(row.getCell(9)),
                    decimal(row.getCell(10)),
                    spend,
                    decimal(row.getCell(13)),
                    decimal(row.getCell(14)),
                    sales,
                    decimal(row.getCell(16)),
                    decimal(row.getCell(17)),
                    decimal(row.getCell(18)),
                    roas,
                    BigDecimal.ZERO,
                    json.write(rawPayload(row, headerRow, Set.of()))
            ));
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static Sheet requiredSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            throw new IllegalArgumentException("历史统计表缺少工作表：" + name);
        }
        return sheet;
    }

    private static Map<String, Integer> headers(Row row) {
        Map<String, Integer> result = new HashMap<>();
        row.forEach(cell -> {
            String name = text(cell);
            if (name != null) {
                result.putIfAbsent(name, cell.getColumnIndex());
            }
        });
        return result;
    }

    private static Map<String, Object> rawPayload(Row row, Row headerRow, Set<String> excluded) {
        Map<String, Object> raw = new LinkedHashMap<>();
        int lastColumn = Math.max(headerRow.getLastCellNum(), row.getLastCellNum());
        for (int column = 0; column < lastColumn; column++) {
            String name = text(headerRow.getCell(column));
            String value = text(row.getCell(column));
            if (name != null && value != null && !excluded.contains(name)) {
                ImportValues.addPreservingDuplicates(raw, name, value);
            }
        }
        return raw;
    }

    private static void require(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            if (!headers.containsKey(name)) {
                throw new IllegalArgumentException("历史订单工作表缺少字段：" + name);
            }
        }
    }

    private static void requireColumn(Row header, int column, String expected) {
        String actual = text(header.getCell(column));
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "历史广告工作表第 " + (column + 1) + " 列应为 " + expected + "，实际为 " + actual
            );
        }
    }

    private static String value(Row row, Map<String, Integer> headers, String name) {
        Integer column = headers.get(name);
        return column == null ? null : text(row.getCell(column));
    }

    private static String text(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> ImportValues.blankToNull(cell.getStringCellValue());
                case NUMERIC -> numericText(cell.getNumericCellValue());
                case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
                default -> null;
            };
        }
        return ImportValues.text(cell);
    }

    private static String numericText(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal decimal(Cell cell) {
        return ImportValues.decimal(text(cell));
    }

    private static long whole(Cell cell) {
        return decimal(cell).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static LocalDate cellDate(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        if (type == CellType.NUMERIC) {
            return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate();
        }
        return ImportValues.date(text(cell));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !value.startsWith("#")) {
                return value;
            }
        }
        return null;
    }
}
