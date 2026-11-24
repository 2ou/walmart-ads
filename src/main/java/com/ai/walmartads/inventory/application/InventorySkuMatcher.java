package com.ai.walmartads.inventory.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Align source SKUs only by exact match or an unambiguous WFS numeric-suffix variant. */
final class InventorySkuMatcher {
    private final Map<String, String> exact = new LinkedHashMap<>();
    private final Map<String, String> uniqueFamilies = new LinkedHashMap<>();

    InventorySkuMatcher(Collection<String> skus) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String raw : skus) {
            if (raw == null || raw.isBlank()) continue;
            String sku = raw.trim();
            if (exact.putIfAbsent(key(sku), sku) != null) continue;
            String family = family(sku);
            counts.merge(family, 1, Integer::sum);
            uniqueFamilies.putIfAbsent(family, sku);
        }
        counts.forEach((family, count) -> { if (count != 1) uniqueFamilies.remove(family); });
    }

    String resolve(String sku) {
        if (sku == null || sku.isBlank()) return null;
        String match = exact.get(key(sku));
        return match != null ? match : uniqueFamilies.get(family(sku));
    }

    Result align(Map<String, Double> source) {
        Map<String, Double> matched = new LinkedHashMap<>();
        Map<String, Double> unmatched = new LinkedHashMap<>();
        source.forEach((sku, units) -> {
            double quantity = units == null ? 0 : units;
            String match = resolve(sku);
            if (match == null) {
                if (quantity != 0) unmatched.merge(sku == null ? "（缺少 SKU）" : sku, quantity, Double::sum);
            } else matched.merge(match, quantity, Double::sum);
        });
        return new Result(Map.copyOf(matched), Map.copyOf(unmatched));
    }

    private static String key(String sku) { return sku.trim().toUpperCase(Locale.ROOT); }
    private static String family(String sku) { return key(sku).replaceFirst("-WFS\\d+$", "-WFS"); }

    record Result(Map<String, Double> matched, Map<String, Double> unmatched) { }
}
