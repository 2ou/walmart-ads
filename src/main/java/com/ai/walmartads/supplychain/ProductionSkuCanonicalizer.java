package com.ai.walmartads.supplychain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 把供应链 SKU 安全地对齐到当前产品库 SKU；只接受精确匹配或唯一的 WFS 数字后缀变体。 */
final class ProductionSkuCanonicalizer {

    private final Map<String, String> exact;
    private final Map<String, String> uniqueFamilies;

    ProductionSkuCanonicalizer(Collection<String> productSkus) {
        Map<String, String> exactRows = new LinkedHashMap<>();
        Map<String, String> families = new LinkedHashMap<>();
        Map<String, Integer> familyCounts = new LinkedHashMap<>();
        for (String raw : productSkus == null ? java.util.List.<String>of() : productSkus) {
            String sku = trim(raw);
            if (sku == null) continue;
            exactRows.putIfAbsent(key(sku), sku);
            String family = familyKey(sku);
            familyCounts.merge(family, 1, Integer::sum);
            families.putIfAbsent(family, sku);
        }
        familyCounts.forEach((family, count) -> {
            if (count != 1) families.remove(family);
        });
        this.exact = Map.copyOf(exactRows);
        this.uniqueFamilies = Map.copyOf(families);
    }

    String resolve(String sourceSku) {
        String source = trim(sourceSku);
        if (source == null) return sourceSku;
        String exactMatch = exact.get(key(source));
        if (exactMatch != null) return exactMatch;
        return uniqueFamilies.getOrDefault(familyKey(source), source);
    }

    private static String familyKey(String sku) {
        return key(sku).replaceFirst("-WFS\\d+$", "-WFS");
    }

    private static String key(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
