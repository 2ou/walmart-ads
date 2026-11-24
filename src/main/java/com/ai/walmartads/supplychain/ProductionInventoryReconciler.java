package com.ai.walmartads.supplychain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reconciles a production snapshot with planned delivery quantities exactly once per order and SKU. */
final class ProductionInventoryReconciler {
    record Delivery(String orderNo, String sku, String planFlag, BigDecimal quantity) { }

    static List<CurrentProductionOrder> reconcile(List<CurrentProductionOrder> production,
                                                   List<Delivery> deliveries,
                                                   ProductionSkuCanonicalizer canonicalizer) {
        Map<String, BigDecimal> planned = new HashMap<>();
        Map<String, BigDecimal> unplanned = new HashMap<>();
        Map<String, Boolean> unknown = new HashMap<>();
        Map<String, Boolean> hasDelivery = new HashMap<>();
        for (Delivery delivery : deliveries) {
            String key = key(delivery.orderNo(), canonicalizer.resolve(delivery.sku()));
            if (key == null) continue;
            hasDelivery.put(key, true);
            String flag = delivery.planFlag() == null ? "" : delivery.planFlag().trim();
            BigDecimal qty = nonNegative(delivery.quantity());
            if ("1".equals(flag)) planned.merge(key, qty, BigDecimal::add);
            else if ("0".equals(flag)) unplanned.merge(key, qty, BigDecimal::add);
            else if (qty.signum() > 0) unknown.put(key, true);
        }
        Map<String, BigDecimal> capacity = new HashMap<>();
        for (CurrentProductionOrder row : production) {
            String key = key(row.orderNo(), canonicalizer.resolve(row.sku()));
            if (key != null && row.productionQty() != null) {
                capacity.merge(key, nonNegative(row.productionQty()), BigDecimal::add);
            }
        }
        Map<String, Boolean> overPlanned = new HashMap<>();
        planned.forEach((key, qty) -> {
            if (capacity.containsKey(key) && qty.compareTo(capacity.get(key)) > 0) overPlanned.put(key, true);
        });
        List<CurrentProductionOrder> result = new ArrayList<>(production.size());
        for (CurrentProductionOrder row : production) {
            String sku = canonicalizer.resolve(row.sku());
            String key = key(row.orderNo(), sku);
            if (key == null || row.productionQty() == null) {
                result.add(new CurrentProductionOrder(row.orderNo(), row.styleNo(), sku, row.orderDate(),
                        row.cuttingQty(), row.productionQty(), row.wipQty(), row.factoryReturnDate(), row.source(),
                        null, null, "UNMATCHABLE"));
                continue;
            }
            BigDecimal produced = nonNegative(row.productionQty());
            BigDecimal availablePlanned = planned.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal allocatedPlanned = availablePlanned.min(produced);
            planned.put(key, availablePlanned.subtract(allocatedPlanned));
            BigDecimal remaining = produced.subtract(allocatedPlanned);
            BigDecimal availableUnplanned = unplanned.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal allocatedUnplanned = availableUnplanned.min(remaining);
            unplanned.put(key, availableUnplanned.subtract(allocatedUnplanned));
            String status = unknown.containsKey(key) ? "UNKNOWN_PLAN"
                    : overPlanned.containsKey(key) ? "OVER_PLANNED"
                    : !hasDelivery.containsKey(key) ? "NO_DELIVERY" : "RECONCILED";
            result.add(new CurrentProductionOrder(row.orderNo(), row.styleNo(), sku, row.orderDate(),
                    row.cuttingQty(), row.productionQty(), remaining, row.factoryReturnDate(), row.source(),
                    allocatedPlanned, allocatedUnplanned, status));
        }
        return List.copyOf(result);
    }

    private static String key(String order, String sku) {
        if (order == null || order.isBlank() || sku == null || sku.isBlank()) return null;
        return order.trim().toUpperCase(Locale.ROOT) + "\u0000" + sku.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
