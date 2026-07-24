package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.InventorySnapshot;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WeeklyInventoryWorkbookParser {

    private static final String SHEET_NAME = "WFS库存";

    private final JsonPayloadWriter json;

    public WeeklyInventoryWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<InventorySnapshot> parse(Path path, ImportFile importFile) throws IOException {
        LocalDateTime snapshotAt = importFile.getSnapshotAt();
        if (snapshotAt == null) {
            throw new IllegalArgumentException("周报库存缺少文件名快照日期：" + path.getFileName());
        }

        List<InventorySnapshot> records = new ArrayList<>();
        int sourceRows = 0;
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                throw new IllegalArgumentException("周报缺少工作表：" + SHEET_NAME);
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.US);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("周报库存工作表没有表头：" + path.getFileName());
            }
            Map<String, Integer> headers = headers(headerRow, formatter, evaluator);
            require(headers, "SKU", "Walmart item ID", "Available to sell");

            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = value(row, headers, "SKU", formatter, evaluator);
                if (sku == null) {
                    continue;
                }
                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, index) -> {
                    String cellValue = text(row, index, formatter, evaluator);
                    if (cellValue != null) {
                        raw.put(name, cellValue);
                    }
                });
                String parentSku = firstNonBlank(
                        value(row, headers, "品号2", formatter, evaluator),
                        value(row, headers, "品号", formatter, evaluator),
                        ImportValues.parentSku(sku)
                );
                records.add(new InventorySnapshot(
                        importFile,
                        snapshotAt,
                        sku,
                        parentSku,
                        value(row, headers, "Walmart item ID", formatter, evaluator),
                        value(row, headers, "GTIN", formatter, evaluator),
                        value(row, headers, "Item name", formatter, evaluator),
                        value(row, headers, "Status", formatter, evaluator),
                        ImportValues.decimal(value(row, headers, "Daily sales", formatter, evaluator)),
                        ImportValues.decimal(value(row, headers, "Daily units sold", formatter, evaluator)),
                        ImportValues.decimal(value(row, headers, "Available to sell", formatter, evaluator)),
                        ImportValues.decimal(value(row, headers, "Reservations", formatter, evaluator)),
                        ImportValues.decimal(value(row, headers, "Inbound units", formatter, evaluator)),
                        ImportValues.decimal(value(row, headers, "Days of supply", formatter, evaluator)),
                        ImportValues.date(value(row, headers, "Out-of-stock date", formatter, evaluator)),
                        ImportValues.decimal(value(row, headers, "Suggested units to send", formatter, evaluator)),
                        value(row, headers, "Brand", formatter, evaluator),
                        ImportValues.decimal(value(row, headers, "Surplus units", formatter, evaluator)),
                        ImportValues.decimal(value(row, headers, "Sell through rate", formatter, evaluator)),
                        value(row, headers, "Publishing status", formatter, evaluator),
                        value(row, headers, "Item lifecycle", formatter, evaluator),
                        json.write(raw)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static Map<String, Integer> headers(
            Row row,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        Map<String, Integer> result = new LinkedHashMap<>();
        row.forEach(cell -> {
            String name = ImportValues.blankToNull(formatter.formatCellValue(cell, evaluator));
            if (name != null) {
                result.put(name, cell.getColumnIndex());
            }
        });
        return result;
    }

    private static String value(
            Row row,
            Map<String, Integer> headers,
            String key,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        Integer index = headers.get(key);
        return index == null ? null : text(row, index, formatter, evaluator);
    }

    private static String text(Row row, int index, DataFormatter formatter, FormulaEvaluator evaluator) {
        return ImportValues.blankToNull(formatter.formatCellValue(row.getCell(index), evaluator));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void require(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            if (!headers.containsKey(name)) {
                throw new IllegalArgumentException("周报库存缺少字段：" + name);
            }
        }
    }
}
