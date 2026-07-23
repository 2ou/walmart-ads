package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.OrderRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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
public class OrderWorkbookParser {

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

    public OrderWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<OrderRecord> parse(Path path, ImportFile importFile) throws IOException {
        LocalDate reportDate = importFile.getReportDate();
        if (reportDate == null) {
            throw new IllegalArgumentException("订单文件缺少日期目录：" + path);
        }
        List<OrderRecord> records = new ArrayList<>();
        int sourceRows = 0;
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet("Po Details");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headers = headers(headerRow);
            require(headers, "Order Date", "Order#", "SKU", "Qty");
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                LocalDate orderDate = ImportValues.date(row.getCell(headers.get("Order Date")));
                if (!reportDate.equals(orderDate)) {
                    continue;
                }
                String sku = value(row, headers, "SKU");
                String orderNumber = value(row, headers, "Order#");
                if (sku == null || orderNumber == null) {
                    continue;
                }
                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, index) -> {
                    if (!PRIVATE_FIELDS.contains(name)) {
                        String value = ImportValues.text(row.getCell(index));
                        if (value != null) {
                            raw.put(name, value);
                        }
                    }
                });
                records.add(new OrderRecord(
                        importFile,
                        reportDate,
                        orderDate,
                        value(row, headers, "PO#"),
                        orderNumber,
                        value(row, headers, "Line#"),
                        sku,
                        ImportValues.parentSku(sku),
                        value(row, headers, "UPC"),
                        value(row, headers, "Status"),
                        ImportValues.decimal(value(row, headers, "Qty")),
                        ImportValues.decimal(value(row, headers, "Item Cost")),
                        ImportValues.decimal(value(row, headers, "Original Price")),
                        ImportValues.decimal(value(row, headers, "Shipping Cost")),
                        value(row, headers, "Item Description"),
                        value(row, headers, "Shipping Method"),
                        value(row, headers, "Fulfillment Type"),
                        json.write(raw)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static Map<String, Integer> headers(Row row) {
        Map<String, Integer> result = new HashMap<>();
        row.forEach(cell -> {
            String name = ImportValues.text(cell);
            if (name != null) {
                result.put(name, cell.getColumnIndex());
            }
        });
        return result;
    }

    private static String value(Row row, Map<String, Integer> headers, String key) {
        Integer index = headers.get(key);
        return index == null ? null : ImportValues.text(row.getCell(index));
    }

    private static void require(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            if (!headers.containsKey(name)) {
                throw new IllegalArgumentException("订单报表缺少字段：" + name);
            }
        }
    }
}
