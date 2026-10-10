package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.DomesticInventorySnapshot;
import com.ai.walmartads.importing.domain.ImportFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses the domestic inventory workbook: SKU + available quantity. */
@Component
public class DomesticInventoryWorkbookParser {

    private final JsonPayloadWriter json;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public DomesticInventoryWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<DomesticInventorySnapshot> parse(Path path, ImportFile importFile) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input, importFile);
        }
    }

    public ParsedRows<DomesticInventorySnapshot> parse(byte[] content, ImportFile importFile) throws IOException {
        try (InputStream input = new ByteArrayInputStream(content)) {
            return parse(input, importFile);
        }
    }

    private ParsedRows<DomesticInventorySnapshot> parse(InputStream input, ImportFile importFile) throws IOException {
        List<DomesticInventorySnapshot> rows = new ArrayList<>();
        int sourceRows = 0;
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = findHeader(sheet);
            Map<String, Integer> headers = headers(header);
            Integer skuColumn = find(headers, "sku");
            Integer availableColumn = find(headers, "可用量", "availableunits", "availableqty");
            if (skuColumn == null || availableColumn == null) {
                throw new IllegalArgumentException("Domestic inventory workbook requires SKU and available quantity columns");
            }
            LocalDateTime snapshotAt = importFile.getSnapshotAt() == null
                    ? importFile.getSourceModifiedAt()
                    : importFile.getSnapshotAt();
            for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = text(row.getCell(skuColumn));
                if (sku == null) {
                    continue;
                }
                BigDecimal available = number(row.getCell(availableColumn));
                if (available == null || available.signum() < 0) {
                    throw new IllegalArgumentException("Invalid domestic available quantity for SKU " + sku);
                }
                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, index) -> {
                    String value = text(row.getCell(index));
                    if (value != null) {
                        raw.put(name, value);
                    }
                });
                rows.add(new DomesticInventorySnapshot(importFile, snapshotAt, sku, available, json.write(raw)));
            }
        }
        return new ParsedRows<>(sourceRows, rows);
    }

    private Row findHeader(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 30);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, Integer> headers = headers(row);
            if (find(headers, "sku") != null && find(headers, "可用量", "availableunits", "availableqty") != null) {
                return row;
            }
        }
        throw new IllegalArgumentException("Domestic inventory workbook header was not found");
    }

    private Map<String, Integer> headers(Row row) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Cell cell : row) {
            String value = text(cell);
            if (value != null) {
                result.put(normalize(value), cell.getColumnIndex());
            }
        }
        return result;
    }

    private static Integer find(Map<String, Integer> headers, String... expected) {
        for (String name : expected) {
            Integer exact = headers.get(normalize(name));
            if (exact != null) {
                return exact;
            }
        }
        for (Map.Entry<String, Integer> entry : headers.entrySet()) {
            for (String name : expected) {
                if (entry.getKey().contains(normalize(name))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        String value = ImportValues.blankToNull(formatter.formatCellValue(cell));
        return value == null ? null : value.trim();
    }

    private BigDecimal number(Cell cell) {
        String value = text(cell);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }
}
