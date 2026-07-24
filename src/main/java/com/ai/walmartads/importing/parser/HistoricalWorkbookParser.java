package com.ai.walmartads.importing.parser;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.OrderRecord;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final List<DateTimeFormatter> EXTRA_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("M/d/yy", Locale.US),
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("yyyy/M/d", Locale.US)
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

        OrderSheetHandler orderHandler = new OrderSheetHandler(importFile, endDateExclusive);
        AdSheetHandler adHandler = new AdSheetHandler(importFile, endDateExclusive);
        boolean ordersFound = false;
        boolean adsFound = false;

        try (OPCPackage pkg = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream input = sheets.next()) {
                    String sheetName = sheets.getSheetName();
                    if (ORDER_SHEET.equals(sheetName)) {
                        parseSheet(input, styles, strings, orderHandler);
                        ordersFound = true;
                    } else if (AD_SHEET.equals(sheetName)) {
                        parseSheet(input, styles, strings, adHandler);
                        adsFound = true;
                    }
                }
            }
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IOException("无法流式读取历史统计表：" + path, exception);
        }

        if (!ordersFound) {
            throw new IllegalArgumentException("历史统计表缺少工作表：" + ORDER_SHEET);
        }
        if (!adsFound) {
            throw new IllegalArgumentException("历史统计表缺少工作表：" + AD_SHEET);
        }
        return new HistoricalWorkbookRows(
                orderHandler.sourceRows + adHandler.sourceRows,
                orderHandler.records,
                adHandler.records
        );
    }

    private static void parseSheet(
            InputStream input,
            StylesTable styles,
            ReadOnlySharedStringsTable strings,
            XSSFSheetXMLHandler.SheetContentsHandler handler
    ) throws Exception {
        XMLReader parser = XMLHelper.newXMLReader();
        parser.setContentHandler(new XSSFSheetXMLHandler(
                styles,
                null,
                strings,
                handler,
                new DataFormatter(Locale.US),
                false
        ));
        parser.parse(new InputSource(input));
    }

    private abstract static class StreamingSheetHandler
            implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final Map<Integer, String> currentRow = new HashMap<>();

        @Override
        public void startRow(int rowNum) {
            currentRow.clear();
        }

        @Override
        public void endRow(int rowNum) {
            if (!currentRow.isEmpty()) {
                acceptRow(rowNum, Map.copyOf(currentRow));
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference == null || formattedValue == null) {
                return;
            }
            String value = ImportValues.blankToNull(formattedValue);
            if (value != null) {
                currentRow.put((int) new CellReference(cellReference).getCol(), value);
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Headers and footers are not business rows.
        }

        protected abstract void acceptRow(int rowNum, Map<Integer, String> values);
    }

    private final class OrderSheetHandler extends StreamingSheetHandler {

        private final ImportFile importFile;
        private final LocalDate endDateExclusive;
        private final List<OrderRecord> records = new ArrayList<>();
        private Map<String, Integer> headers;
        private Map<Integer, String> columnNames;
        private int sourceRows;

        private OrderSheetHandler(ImportFile importFile, LocalDate endDateExclusive) {
            this.importFile = importFile;
            this.endDateExclusive = endDateExclusive;
        }

        @Override
        protected void acceptRow(int rowNum, Map<Integer, String> values) {
            if (rowNum == 0) {
                columnNames = Map.copyOf(values);
                headers = new HashMap<>();
                values.forEach((column, name) -> headers.putIfAbsent(name, column));
                require(headers, "Order Date", "Order#", "SKU", "Qty", "Shipping Cost", "Tax");
                return;
            }
            sourceRows++;
            LocalDate orderDate = date(value(values, headers, "Order Date"));
            if (orderDate == null || !orderDate.isBefore(endDateExclusive)) {
                return;
            }
            String sku = value(values, headers, "SKU");
            String orderNumber = value(values, headers, "Order#");
            if (sku == null) {
                return;
            }

            Map<String, Object> raw = rawPayload(values, columnNames, PRIVATE_FIELDS);
            if (orderNumber == null) {
                orderNumber = "HISTORY-" + orderDate + "-" + (rowNum + 1);
                raw.put("_syntheticOrderNumber", orderNumber);
            }
            String itemCost = value(values, headers, "Shipping Cost");
            String actualShippingCost = value(values, headers, "Tax");
            raw.put("_normalizedItemCost", itemCost);
            raw.put("_normalizedShippingCost", actualShippingCost);

            records.add(new OrderRecord(
                    importFile,
                    orderDate,
                    orderDate,
                    value(values, headers, "PO#"),
                    orderNumber,
                    value(values, headers, "Line#"),
                    sku,
                    ImportValues.parentSku(sku),
                    value(values, headers, "UPC"),
                    value(values, headers, "Status"),
                    ImportValues.decimal(value(values, headers, "Qty")),
                    ImportValues.decimal(itemCost),
                    ImportValues.decimal(itemCost),
                    ImportValues.decimal(actualShippingCost),
                    value(values, headers, "Item Description"),
                    value(values, headers, "Shipping Method"),
                    value(values, headers, "Fulfillment Entity"),
                    json.write(raw)
            ));
        }
    }

    private final class AdSheetHandler extends StreamingSheetHandler {

        private final ImportFile importFile;
        private final LocalDate endDateExclusive;
        private final List<AdPerformance> records = new ArrayList<>();
        private Map<Integer, String> columnNames;
        private int sourceRows;

        private AdSheetHandler(ImportFile importFile, LocalDate endDateExclusive) {
            this.importFile = importFile;
            this.endDateExclusive = endDateExclusive;
        }

        @Override
        protected void acceptRow(int rowNum, Map<Integer, String> values) {
            if (rowNum == 0) {
                columnNames = Map.copyOf(values);
                requireColumn(values, 0, "广告活动名称");
                requireColumn(values, 4, "产品ID");
                requireColumn(values, 8, "展示次数");
                requireColumn(values, 11, "广告支出");
                requireColumn(values, 32, "日期");
                return;
            }
            sourceRows++;
            LocalDate reportDate = date(values.get(32));
            if (reportDate == null || !reportDate.isBefore(endDateExclusive)) {
                return;
            }
            String campaignName = values.get(0);
            if (campaignName == null) {
                return;
            }

            String parentSku = firstNonBlank(
                    values.get(34),
                    values.get(33),
                    ImportValues.parentSku(campaignName)
            );
            BigDecimal spend = ImportValues.decimal(values.get(11));
            BigDecimal sales = ImportValues.decimal(values.get(15));
            BigDecimal roas = ImportValues.decimal(values.get(30));
            if (roas.signum() == 0 && spend.signum() > 0) {
                roas = sales.divide(spend, 8, RoundingMode.HALF_UP);
            }

            records.add(new AdPerformance(
                    importFile,
                    reportDate,
                    ImportFileType.AD_AUTO,
                    campaignName,
                    values.get(1),
                    "Sponsored Products (Auto)",
                    null,
                    BigDecimal.ZERO,
                    values.get(2),
                    values.get(3),
                    values.get(4),
                    null,
                    parentSku,
                    values.get(5),
                    null,
                    null,
                    null,
                    ImportValues.decimal(values.get(6)),
                    ImportValues.decimal(values.get(7)),
                    ImportValues.whole(values.get(8)),
                    ImportValues.whole(values.get(9)),
                    ImportValues.decimal(values.get(10)),
                    spend,
                    ImportValues.decimal(values.get(13)),
                    ImportValues.decimal(values.get(14)),
                    sales,
                    ImportValues.decimal(values.get(16)),
                    ImportValues.decimal(values.get(17)),
                    ImportValues.decimal(values.get(18)),
                    roas,
                    BigDecimal.ZERO,
                    json.write(rawPayload(values, columnNames, Set.of()))
            ));
        }
    }

    private static Map<String, Object> rawPayload(
            Map<Integer, String> values,
            Map<Integer, String> columnNames,
            Set<String> excluded
    ) {
        Map<String, Object> raw = new LinkedHashMap<>();
        values.forEach((column, value) -> {
            String name = columnNames.get(column);
            if (name != null && !excluded.contains(name)) {
                ImportValues.addPreservingDuplicates(raw, name, value);
            }
        });
        return raw;
    }

    private static void require(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            if (!headers.containsKey(name)) {
                throw new IllegalArgumentException("历史订单工作表缺少字段：" + name);
            }
        }
    }

    private static void requireColumn(Map<Integer, String> headers, int column, String expected) {
        String actual = headers.get(column);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "历史广告工作表第 " + (column + 1) + " 列应为 " + expected + "，实际为 " + actual
            );
        }
    }

    private static String value(
            Map<Integer, String> values,
            Map<String, Integer> headers,
            String name
    ) {
        Integer column = headers.get(name);
        return column == null ? null : values.get(column);
    }

    private static LocalDate date(String value) {
        LocalDate parsed = ImportValues.date(value);
        if (parsed != null) {
            return parsed;
        }
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter formatter : EXTRA_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next known display format.
            }
        }
        try {
            double serial = new BigDecimal(value).doubleValue();
            if (serial > 1) {
                return DateUtil.getLocalDateTime(serial).toLocalDate();
            }
        } catch (NumberFormatException ignored) {
            // Not an Excel serial date.
        }
        return null;
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
