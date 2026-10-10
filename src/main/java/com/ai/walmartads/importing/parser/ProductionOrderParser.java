package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ProductionOrderRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析「生产中订单」Excel，抽取生产订单（做货）明细。
 *
 * 单元格读取采用“缓存值”策略：对公式单元格取 Excel 上次计算的缓存结果
 * （getCachedFormulaResultType + 类型化取值），而非用 FormulaEvaluator 重新求值——
 * 复杂公式或除零会令 POI 重新求值失败；同时 formatCellValue(cell) 不带 evaluator
 * 时只会返回公式文本本身，并非计算结果。
 *
 * 做货数量规则：裁床数 > 0 取裁床数，否则取开单数（派生列 productionQty）。
 */
@Component
public class ProductionOrderParser {

    private static final String SHEET_NAME = "生产中订单数据";

    private final JsonPayloadWriter json;

    public ProductionOrderParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<ProductionOrderRecord> parse(Path path, ImportFile importFile) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input, importFile);
        }
    }

    public ParsedRows<ProductionOrderRecord> parse(byte[] content, ImportFile importFile) throws IOException {
        try (InputStream input = new ByteArrayInputStream(content)) {
            return parse(input, importFile);
        }
    }

    private ParsedRows<ProductionOrderRecord> parse(InputStream input, ImportFile importFile) throws IOException {
        List<ProductionOrderRecord> records = new ArrayList<>();
        int sourceRows = 0;
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            if (sheet == null) {
                throw new IllegalArgumentException("生产订单表缺少工作表");
            }
            Row headerRow = findHeaderRow(sheet);
            Map<String, Integer> headers = headers(headerRow);
            Integer skuIndex = require(headers, "SKU");
            Integer styleNoIndex = resolve(headers, "款号");
            Integer orderNoIndex = resolve(headers, "订单编号");
            Integer orderDateIndex = resolve(headers, "下单日期");
            if (orderDateIndex == null) {
                orderDateIndex = resolve(headers, "下单时间");
            }
            if (orderDateIndex == null) {
                orderDateIndex = resolve(headers, "订单日期");
            }
            Integer modelIndex = resolve(headers, "型号");
            Integer openQtyIndex = resolve(headers, "开单数");
            Integer cuttingQtyIndex = resolve(headers, "裁床数");
            Integer shipQtyIndex = resolve(headers, "发货数");
            Integer warehouseReceiveQtyIndex = resolve(headers, "仓库收货数");
            Integer qcPassQtyIndex = resolve(headers, "质检合格数");
            Integer unfinishedQtyIndex = resolve(headers, "未完成数");
            Integer factoryReturnDateIndex = resolve(headers, "工厂确认回货日期");
            Integer storeIndex = resolve(headers, "所属店铺");
            Integer orderTypeIndex = resolve(headers, "订单类型");

            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = cellText(row.getCell(skuIndex));
                if (sku == null) {
                    continue;
                }
                BigDecimal openQty = decimal(row.getCell(openQtyIndex));
                BigDecimal cuttingQty = decimal(row.getCell(cuttingQtyIndex));
                BigDecimal productionQty = cuttingQty != null && cuttingQty.compareTo(BigDecimal.ZERO) > 0
                        ? cuttingQty
                        : openQty;

                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, index) -> {
                    String cellValue = cellText(row.getCell(index));
                    if (cellValue != null) {
                        raw.put(name, cellValue);
                    }
                });
                records.add(new ProductionOrderRecord(
                        importFile,
                        sku,
                        cellText(row.getCell(styleNoIndex)),
                        cellText(row.getCell(orderNoIndex)),
                        ImportValues.date(orderDateIndex == null ? null : row.getCell(orderDateIndex)),
                        cellText(row.getCell(modelIndex)),
                        openQty,
                        cuttingQty,
                        productionQty,
                        decimal(row.getCell(shipQtyIndex)),
                        decimal(row.getCell(warehouseReceiveQtyIndex)),
                        decimal(row.getCell(qcPassQtyIndex)),
                        decimal(row.getCell(unfinishedQtyIndex)),
                        cellText(row.getCell(factoryReturnDateIndex)),
                        cellText(row.getCell(storeIndex)),
                        cellText(row.getCell(orderTypeIndex)),
                        json.write(raw)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static Map<String, Integer> headers(Row row) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Cell cell : row) {
            if (cell == null) {
                continue;
            }
            String raw = ImportValues.blankToNull(cellText(cell));
            if (raw != null) {
                result.put(normalize(raw), cell.getColumnIndex());
            }
        }
        return result;
    }

    /**
     * 定位表头行：扫描前若干行，返回首个同时含 “sku” 与 “开单数/裁床数” 的行（避免误判标题行）。
     */
    private static Row findHeaderRow(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 30);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            boolean hasSku = false;
            boolean hasQtyKey = false;
            for (Cell cell : row) {
                if (cell == null) {
                    continue;
                }
                String value = ImportValues.blankToNull(cellText(cell));
                if (value == null) {
                    continue;
                }
                String normalized = normalize(value);
                if (normalized.contains("sku")) {
                    hasSku = true;
                }
                if (normalized.contains("开单数") || normalized.contains("裁床数")) {
                    hasQtyKey = true;
                }
            }
            if (hasSku && hasQtyKey) {
                return row;
            }
        }
        throw new IllegalArgumentException("生产订单表找不到含 SKU/开单数 的表头行");
    }

    private static Integer require(Map<String, Integer> headers, String keyword) {
        Integer index = resolve(headers, keyword);
        if (index == null) {
            throw new IllegalArgumentException("生产订单表缺少字段：" + keyword);
        }
        return index;
    }

    private static Integer resolve(Map<String, Integer> headers, String... keywords) {
        for (Map.Entry<String, Integer> entry : headers.entrySet()) {
            boolean matches = true;
            for (String keyword : keywords) {
                if (!entry.getKey().contains(normalize(keyword))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            return switch (cell.getCellType()) {
                case STRING -> ImportValues.blankToNull(cell.getStringCellValue());
                case NUMERIC -> numberText(cell);
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cellTextFromCached(cell);
                case _NONE, BLANK, ERROR -> null;
                default -> null;
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String cellTextFromCached(Cell cell) {
        try {
            return switch (cell.getCachedFormulaResultType()) {
                case NUMERIC -> numberText(cell);
                case STRING -> ImportValues.blankToNull(cell.getStringCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case _NONE, ERROR -> null;
                default -> null;
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String numberText(Cell cell) {
        double value = cell.getNumericCellValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal decimal(Cell cell) {
        String value = ImportValues.blankToNull(cellText(cell));
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace("$", "").replace(",", "").replace("%", "");
        if (normalized.isEmpty() || normalized.startsWith("#")) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(normalized);
            return value.contains("%")
                    ? parsed.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                    : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "")
                .replace("（", "(")
                .replace("）", ")")
                .replace("【", "[")
                .replace("】", "]")
                .toLowerCase(Locale.ROOT);
    }
}
