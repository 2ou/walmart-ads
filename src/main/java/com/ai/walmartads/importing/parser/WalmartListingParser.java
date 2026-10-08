package com.ai.walmartads.importing.parser;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析「沃尔玛商品」在线列表导出（xlsx，单工作表 sheet1），按 SKU 全变体匹配回写产品库价格。
 *
 * 列约定（按表头中文模糊匹配，不依赖列顺序）：
 *   SKU(5) → 匹配键；价格(8) → price；划线价(11) → strikethroughPrice；商品ID(1) → productId；
 *   upc(18)；gtin(17)；状态(6) → listingStatus；店铺(7) → store；品名(4) → parentSku（仅参考）。
 * 不在产品库的 SKU 不参与回写（按"不在产品库不分析"口径跳过）。
 */
@Component
public class WalmartListingParser {

    public ParsedRows<WalmartListingRow> parse(Path path, ImportFile importFile) throws IOException {
        List<WalmartListingRow> records = new ArrayList<>();
        int sourceRows = 0;
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException("沃尔玛商品表缺少表头行");
            }
            Map<String, Integer> headers = new LinkedHashMap<>();
            for (Cell cell : headerRow) {
                String name = ImportValues.blankToNull(ImportValues.text(cell));
                if (name != null) {
                    headers.put(normalize(name), cell.getColumnIndex());
                }
            }
            Integer skuIndex = resolve(headers, "sku");
            if (skuIndex == null) {
                throw new IllegalArgumentException("沃尔玛商品表缺少 SKU 列");
            }
            Integer priceIndex = resolve(headers, "价格");
            Integer strikethroughPriceIndex = resolve(headers, "划线价");
            Integer productIdIndex = resolve(headers, "商品id");
            Integer upcIndex = resolve(headers, "upc");
            Integer gtinIndex = resolve(headers, "gtin");
            Integer statusIndex = resolve(headers, "状态");
            Integer storeIndex = resolve(headers, "店铺");
            Integer parentIndex = resolve(headers, "品名");

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = ImportValues.blankToNull(ImportValues.text(row.getCell(skuIndex)));
                if (sku == null) {
                    continue;
                }
                String priceText = priceIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(priceIndex)))
                        : null;
                BigDecimal price = priceText == null ? null : ImportValues.decimal(priceText);
                String strikethroughPriceText = strikethroughPriceIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(strikethroughPriceIndex)))
                        : null;
                BigDecimal strikethroughPrice = strikethroughPriceText == null
                        ? null
                        : ImportValues.decimal(strikethroughPriceText);
                String productId = productIdIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(productIdIndex)))
                        : null;
                String upc = upcIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(upcIndex)))
                        : null;
                String gtin = gtinIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(gtinIndex)))
                        : null;
                String listingStatus = statusIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(statusIndex)))
                        : null;
                String store = storeIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(storeIndex)))
                        : null;
                String parentSku = parentIndex != null
                        ? ImportValues.blankToNull(ImportValues.text(row.getCell(parentIndex)))
                        : null;
                records.add(new WalmartListingRow(
                        sku, parentSku, productId, upc, gtin, price, strikethroughPrice, listingStatus, store
                ));
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static Integer resolve(Map<String, Integer> headers, String keyword) {
        String normalized = normalize(keyword);
        for (Map.Entry<String, Integer> entry : headers.entrySet()) {
            if (entry.getKey().contains(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "")
                .replace("（", "(")
                .replace("）", ")")
                .toLowerCase(Locale.ROOT);
    }
}
