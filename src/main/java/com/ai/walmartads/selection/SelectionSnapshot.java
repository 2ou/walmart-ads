package com.ai.walmartads.selection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Immutable candidate and SKU observations supplied by a completed or partial collection batch. */
public record SelectionSnapshot(String sourceUrl, String zip, LocalDate date, Instant observedAt,
                                int expectedPages, int completedPages, boolean complete,
                                boolean inventoryComplete, Integer declaredCount, String issue, List<Item> items) {
    public record Item(String itemId, String productId, String primaryItemId, String styleKey,
                       String title, String brand, String category, String imageUrl, BigDecimal price,
                       Integer sourcePosition, Integer expectedSkus, List<Variant> variants) {}

    public record Variant(String itemId, String productId, String color, String size,
                          Integer orderLimit, Integer maxOrderQuantity, String availability,
                          String sellerId, String offerId, String fulfillment, Instant observedAt) {}
}
