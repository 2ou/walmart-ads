package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ShipmentRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析 source/货件 中导出的 WFS 货件列表。一个货件的公共字段只出现在第一行，
 * 因此后续 SKU 行需要沿用上一行的货件号、创建时间和本地状态。
 */
@Component
public class ShipmentWorkbookParser {

    private static final Set<String> TRANSIT_STATUSES = Set.of("入库中", "已发货", "已申报");

    private final JsonPayloadWriter json;

    public ShipmentWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<ShipmentRecord> parse(Path path, ImportFile importFile) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = findHeaderRow(sheet, "货件单号", "SKU", "申收差异");
            Headers headers = headers(headerRow);
            int shipmentNo = require(headers, "货件单号");
            int localStatus = require(headers, "货件本地状态");
            int createdAt = require(headers, "创建时间");
            int sku = require(headers, "SKU");
            int difference = require(headers, "申收差异");

            int sourceRows = 0;
            String currentShipmentNo = null;
            String currentStatus = null;
            LocalDateTime currentCreatedAt = null;
            List<ShipmentRecord> records = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String rowShipmentNo = cellText(row, shipmentNo);
                String rowStatus = cellText(row, localStatus);
                LocalDateTime rowCreatedAt = ImportValues.dateTime(row.getCell(createdAt));
                if (rowShipmentNo != null) {
                    currentShipmentNo = rowShipmentNo;
                }
                if (rowStatus != null) {
                    currentStatus = rowStatus;
                }
                if (rowCreatedAt != null) {
                    currentCreatedAt = rowCreatedAt;
                }

                String currentSku = cellText(row, sku);
                if (currentShipmentNo == null || currentSku == null || currentStatus == null
                        || !TRANSIT_STATUSES.contains(currentStatus)) {
                    continue;
                }
                BigDecimal receiptDifference = ImportValues.decimal(cellText(row, difference)).max(BigDecimal.ZERO);
                records.add(new ShipmentRecord(
                        importFile,
                        currentShipmentNo,
                        currentStatus,
                        currentCreatedAt,
                        currentSku,
                        ImportValues.parentSku(currentSku),
                        receiptDifference,
                        json.write(raw(headers, row))
                ));
            }
            return new ParsedRows<>(sourceRows, records);
        }
    }

    static Row findHeaderRow(Sheet sheet, String... requiredColumns) {
        int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 30);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Headers headers = headers(row);
            boolean matches = true;
            for (String column : requiredColumns) {
                if (resolve(headers, column) == null) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return row;
            }
        }
        throw new IllegalArgumentException("货件表缺少必填表头：" + String.join("、", requiredColumns));
    }

    static Headers headers(Row row) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Cell cell : row) {
            String name = ImportValues.blankToNull(ImportValues.cachedText(cell));
            if (name != null) {
                indexes.put(normalize(name), cell.getColumnIndex());
                names.put(cell.getColumnIndex(), name);
            }
        }
        return new Headers(indexes, names);
    }

    static int require(Headers headers, String name) {
        Integer index = resolve(headers, name);
        if (index == null) {
            throw new IllegalArgumentException("货件表缺少字段：" + name);
        }
        return index;
    }

    static Integer resolve(Headers headers, String name) {
        String target = normalize(name);
        return headers.indexes().entrySet().stream()
                .filter(entry -> entry.getKey().equals(target) || entry.getKey().contains(target))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    static Map<String, String> raw(Headers headers, Row row) {
        Map<String, String> raw = new LinkedHashMap<>();
        headers.names().forEach((index, name) -> {
            String value = cellText(row, index);
            if (value != null) {
                raw.put(name, value);
            }
        });
        return raw;
    }

    static String cellText(Row row, int index) {
        return index < 0 ? null : ImportValues.blankToNull(ImportValues.cachedText(row.getCell(index)));
    }

    static String normalize(String value) {
        return value.replaceAll("\\s+", "")
                .replace("（", "(")
                .replace("）", ")")
                .replace("【", "[")
                .replace("】", "]")
                .toLowerCase(Locale.ROOT);
    }

    record Headers(Map<String, Integer> indexes, Map<Integer, String> names) {
    }
}
