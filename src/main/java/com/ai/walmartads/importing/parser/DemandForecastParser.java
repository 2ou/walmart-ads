package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.DemandForecastRecord;
import com.ai.walmartads.importing.domain.ImportFile;
import org.apache.poi.ss.usermodel.Cell;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析「需求预测表单」的 SKU 工作表，抽取仅 Excel 独有的计划值列（按 SKU 关联）。
 * 实际值（颜色/品名/WFS可售/销量/周转/退货率）由系统现有数据源在查询时关联，不在此解析。
 *
 * 单元格读取采用“缓存值”策略：对公式单元格取 Excel 上次计算并保存的缓存结果
 * （getCachedFormulaResultType + 类型化取值），而非用 FormulaEvaluator 重新求值——
 * 需求预测表含 IFERROR/VLOOKUP/除零 等复杂公式，POI 重新求值会失败；同时
 * formatCellValue(cell) 不带 evaluator 时只会返回公式文本本身，并非计算结果。
 */
@Component
public class DemandForecastParser {

    private static final String SHEET_NAME = "SKU";

    private final JsonPayloadWriter json;

    public DemandForecastParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<DemandForecastRecord> parse(Path path, ImportFile importFile) throws IOException {
        List<DemandForecastRecord> records = new ArrayList<>();
        int sourceRows = 0;
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                throw new IllegalArgumentException("需求预测表缺少工作表：" + SHEET_NAME);
            }
            Row headerRow = findHeaderRow(sheet);
            Map<String, Integer> headers = headers(headerRow);
            Integer skuIndex = require(headers, "SKU");
            Integer forecastPmcIndex = resolve(headers, "预计销量", "PMC");
            Integer forecastOpsIndex = resolve(headers, "预计销量", "运营");
            Integer pctGainIndex = resolve(headers, "百分比增益");
            Integer reuseRateIndex = resolve(headers, "退货重复利用率");
            Integer demandPlanIndex = resolve(headers, "需求计划");
            Integer actualOrderIndex = resolve(headers, "实际下单量");
            Integer shipQtyIndex = resolve(headers, "发货量");
            Integer remainingIndex = resolve(headers, "剩余");

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
                Map<String, String> raw = new LinkedHashMap<>();
                headers.forEach((name, index) -> {
                    String cellValue = cellText(row.getCell(index));
                    if (cellValue != null) {
                        raw.put(name, cellValue);
                    }
                });
                records.add(new DemandForecastRecord(
                        importFile,
                        sku,
                        decimal(row.getCell(forecastPmcIndex)),
                        decimal(row.getCell(forecastOpsIndex)),
                        decimal(row.getCell(pctGainIndex)),
                        decimal(row.getCell(reuseRateIndex)),
                        decimal(row.getCell(demandPlanIndex)),
                        decimal(row.getCell(actualOrderIndex)),
                        decimal(row.getCell(shipQtyIndex)),
                        decimal(row.getCell(remainingIndex)),
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
     * 定位表头行：需求预测表的真实表头在第 2 行（第 1 行是分类标题，仅 AJ 列含 “SKU” 字样但无 颜色/品名），
     * 因此不能假设首行即表头。扫描前若干行，返回首个同时含 “sku” 与 “颜色/品名” 的行。
     */
    private static Row findHeaderRow(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 30);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            boolean hasSku = false;
            boolean hasProductKey = false;
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
                if (normalized.contains("颜色") || normalized.contains("品名")) {
                    hasProductKey = true;
                }
            }
            if (hasSku && hasProductKey) {
                return row;
            }
        }
        throw new IllegalArgumentException("需求预测表找不到含 SKU/颜色 的表头行：" + sheet.getSheetName());
    }

    private static Integer require(Map<String, Integer> headers, String keyword) {
        Integer index = resolve(headers, keyword);
        if (index == null) {
            throw new IllegalArgumentException("需求预测表缺少字段：" + keyword);
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

    /**
     * 读取单元格“缓存值”。公式单元格取上次计算的缓存结果，字符串/数值/布尔直接取值。
     * 任何异常（含无法识别的错误缓存）一律按空白处理。
     */
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
