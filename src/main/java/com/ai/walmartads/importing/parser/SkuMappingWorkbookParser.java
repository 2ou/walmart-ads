package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.SkuMapping;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SkuMappingWorkbookParser {

    private static final String SHEET_NAME = "Sheet1";

    private final JsonPayloadWriter json;

    public SkuMappingWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<SkuMapping> parse(Path path, ImportFile importFile) throws IOException {
        List<SkuMapping> mappings = new ArrayList<>();
        int sourceRows = 0;
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                throw new IllegalArgumentException("WFS SKU 映射表缺少工作表：" + SHEET_NAME);
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headers = headers(headerRow);
            require(headers, "SKU", "Item ID", "GTIN", "UPC", "Item Name");

            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = value(row, headers, "SKU");
                String itemId = value(row, headers, "Item ID");
                String gtin = value(row, headers, "GTIN");
                String upc = value(row, headers, "UPC");
                if (sku == null || itemId == null || gtin == null || upc == null) {
                    continue;
                }

                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, column) -> {
                    String cellValue = ImportValues.text(row.getCell(column));
                    if (cellValue != null) {
                        raw.put(name, cellValue);
                    }
                });
                mappings.add(new SkuMapping(
                        importFile,
                        importFile.getSourceModifiedAt(),
                        sku,
                        ImportValues.parentSku(sku),
                        itemId,
                        gtin,
                        upc,
                        value(row, headers, "Item Name"),
                        json.write(raw)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, mappings);
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

    private static String value(Row row, Map<String, Integer> headers, String name) {
        Integer column = headers.get(name);
        return column == null ? null : ImportValues.text(row.getCell(column));
    }

    private static void require(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            if (!headers.containsKey(name)) {
                throw new IllegalArgumentException("WFS SKU 映射表缺少字段：" + name);
            }
        }
    }
}
