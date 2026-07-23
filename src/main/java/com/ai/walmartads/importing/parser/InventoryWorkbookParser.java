package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.InventorySnapshot;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class InventoryWorkbookParser {

    private final JsonPayloadWriter json;

    public InventoryWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<InventorySnapshot> parse(Path path, ImportFile importFile) throws IOException {
        LocalDateTime snapshotAt = importFile.getSnapshotAt() != null
                ? importFile.getSnapshotAt() : importFile.getSourceModifiedAt();
        List<InventorySnapshot> records = new ArrayList<>();
        int sourceRows = 0;
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headers = headers(headerRow);
            require(headers, "SKU", "Item ID", "Available units");
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = value(row, headers, "SKU");
                if (sku == null) {
                    continue;
                }
                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, index) -> {
                    String value = ImportValues.text(row.getCell(index));
                    if (value != null) {
                        raw.put(name, value);
                    }
                });
                records.add(new InventorySnapshot(
                        importFile,
                        snapshotAt,
                        sku,
                        ImportValues.parentSku(sku),
                        value(row, headers, "Item ID"),
                        value(row, headers, "GTIN"),
                        value(row, headers, "Item name"),
                        value(row, headers, "Status"),
                        ImportValues.decimal(value(row, headers, "Daily sales")),
                        ImportValues.decimal(value(row, headers, "Daily units sold")),
                        ImportValues.decimal(value(row, headers, "Available units")),
                        ImportValues.decimal(value(row, headers, "Reserved units")),
                        ImportValues.decimal(value(row, headers, "Inbound units")),
                        ImportValues.decimal(value(row, headers, "Forecasted days of supply")),
                        ImportValues.date(value(row, headers, "Out-of-stock date")),
                        ImportValues.decimal(value(row, headers, "Suggested units")),
                        value(row, headers, "Brand"),
                        ImportValues.decimal(value(row, headers, "Surplus units")),
                        ImportValues.decimal(value(row, headers, "Sell through rate")),
                        value(row, headers, "Publishing status"),
                        value(row, headers, "Item lifecycle"),
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
                throw new IllegalArgumentException("库存报表缺少字段：" + name);
            }
        }
    }
}
