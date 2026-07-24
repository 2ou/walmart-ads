package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.SkuMapping;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
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
import java.util.Locale;

@Component
public class SkuMappingWorkbookParser {

    private static final String SHEET_NAME = "Sheet1";
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.US);

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
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> headers = headers(headerRow, evaluator);
            require(headers, "SKU", "Item ID", "GTIN", "UPC", "Item Name");

            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = value(row, headers, "SKU", evaluator);
                String itemId = value(row, headers, "Item ID", evaluator);
                String gtin = value(row, headers, "GTIN", evaluator);
                String upc = normalizedUpc(value(row, headers, "UPC", evaluator), gtin);
                if (sku == null || itemId == null || gtin == null || upc == null) {
                    continue;
                }

                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, column) -> {
                    String cellValue = text(row.getCell(column), evaluator);
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
                        value(row, headers, "Item Name", evaluator),
                        json.write(raw)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, mappings);
    }

    private static Map<String, Integer> headers(Row row, FormulaEvaluator evaluator) {
        Map<String, Integer> result = new HashMap<>();
        row.forEach(cell -> {
            String name = text(cell, evaluator);
            if (name != null) {
                result.put(name, cell.getColumnIndex());
            }
        });
        return result;
    }

    private static String value(
            Row row,
            Map<String, Integer> headers,
            String name,
            FormulaEvaluator evaluator
    ) {
        Integer column = headers.get(name);
        return column == null ? null : text(row.getCell(column), evaluator);
    }

    private static String text(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        return ImportValues.blankToNull(FORMATTER.formatCellValue(cell, evaluator));
    }

    private static String normalizedUpc(String value, String gtin) {
        if (value != null && value.matches("\\d{12,14}")) {
            return value;
        }
        if (gtin != null && gtin.matches("\\d{14}") && gtin.startsWith("00")) {
            return gtin.substring(2);
        }
        return value;
    }

    private static void require(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            if (!headers.containsKey(name)) {
                throw new IllegalArgumentException("WFS SKU 映射表缺少字段：" + name);
            }
        }
    }
}
