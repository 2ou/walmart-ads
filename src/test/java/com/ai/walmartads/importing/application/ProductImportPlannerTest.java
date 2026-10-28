package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.parser.ParsedRows;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductImportPlannerTest {

    private ProductImportPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ProductImportPlanner(mock(ProductRecordRepository.class), new ObjectMapper());
    }

    @Test
    void mergesOnlyNonBlankUploadFieldsAndPreservesManagedFields() {
        ProductRecord current = product("PNK100-Blue-M", "Old title", "Blue",
                "{\"sku\":\"PNK100-Blue-M\",\"productName\":\"Old title\",\"color\":\"Blue\"}", 1);
        current.setPrice(new BigDecimal("19.99"));
        current.setReviewCount(42);
        ProductRecord upload = product("PNK100-Blue-M", null, "Navy",
                "{\"sku\":\"PNK100-Blue-M\",\"color\":\"Navy\",\"price\":\"1.00\"}", 2);

        ProductImportPlanner.Plan plan = planner.plan(
                new ParsedRows<>(1, List.of(upload)), List.of(current), List.of(current));

        assertThat(plan.conflicts()).isEmpty();
        assertThat(plan.count(ProductImportPlanner.ChangeType.UPDATE)).isEqualTo(1);
        ProductRecord resolved = plan.resolvedRecords().getFirst();
        assertThat(resolved.getProductName()).isEqualTo("Old title");
        assertThat(resolved.getColor()).isEqualTo("Navy");
        assertThat(resolved.getPrice()).isEqualByComparingTo("19.99");
        assertThat(resolved.getReviewCount()).isEqualTo(42);
        assertThat(resolved.getRawPayload()).doesNotContain("1.00").contains("Navy", "Old title");
    }

    @Test
    void plansNewRowsAndSoftRetiresRowsMissingFromFullSnapshot() {
        ProductRecord existing = product("PNK100-Blue-M", "Old", "Blue",
                "{\"sku\":\"PNK100-Blue-M\",\"productName\":\"Old\",\"color\":\"Blue\"}", 1);
        ProductRecord added = product("PNK200-Red-S", "New", "Red",
                "{\"sku\":\"PNK200-Red-S\",\"productName\":\"New\",\"color\":\"Red\"}", 2);

        ProductImportPlanner.Plan plan = planner.plan(
                new ParsedRows<>(1, List.of(added)), List.of(existing), List.of());

        assertThat(plan.count(ProductImportPlanner.ChangeType.ADD)).isEqualTo(1);
        assertThat(plan.count(ProductImportPlanner.ChangeType.RETIRE)).isEqualTo(1);
        assertThat(plan.changes()).filteredOn(ProductImportPlanner.PlannedChange::wholeStyle)
                .singleElement().extracting(ProductImportPlanner.PlannedChange::sku)
                .isEqualTo("PNK100-Blue-M");
    }

    @Test
    void mergesIdenticalDuplicatesButBlocksConflictingDuplicates() {
        ProductRecord first = product("PNK100-Blue-M", "One", "Blue",
                "{\"sku\":\"PNK100-Blue-M\",\"productName\":\"One\"}", 3);
        ProductRecord identical = product("PNK100-Blue-M", "One", "Blue",
                "{\"sku\":\"PNK100-Blue-M\",\"productName\":\"One\"}", 8);
        ProductImportPlanner.Plan merged = planner.plan(
                new ParsedRows<>(2, List.of(first, identical)), List.of(), List.of());
        assertThat(merged.uniqueSkuCount()).isEqualTo(1);
        assertThat(merged.conflicts()).isEmpty();

        ProductRecord conflicting = product("PNK100-Blue-M", "Two", "Blue",
                "{\"sku\":\"PNK100-Blue-M\",\"productName\":\"Two\"}", 9);
        ProductImportPlanner.Plan blocked = planner.plan(
                new ParsedRows<>(2, List.of(first, conflicting)), List.of(), List.of());
        assertThat(blocked.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.rowNumbers()).containsExactly(3, 9);
            assertThat(conflict.fields()).contains("商品名称");
        });
    }

    @Test
    void usesExactSkuIdentity() {
        ProductRecord current = product("PNK100-Blue-M", "Old", "Blue",
                "{\"sku\":\"PNK100-Blue-M\"}", 1);
        ProductRecord differentlyCased = product("pnk100-Blue-M", "New", "Blue",
                "{\"sku\":\"pnk100-Blue-M\"}", 2);

        ProductImportPlanner.Plan plan = planner.plan(
                new ParsedRows<>(1, List.of(differentlyCased)), List.of(current), List.of());

        assertThat(plan.count(ProductImportPlanner.ChangeType.ADD)).isEqualTo(1);
        assertThat(plan.count(ProductImportPlanner.ChangeType.RETIRE)).isEqualTo(1);
    }

    private static ProductRecord product(
            String sku,
            String name,
            String color,
            String raw,
            int rowNumber
    ) {
        ImportFile file = new ImportFile(new ImportBatch("test"), ImportFileType.PRODUCT, null,
                "products.xlsx", "memory://products.xlsx", "0".repeat(64), 10,
                LocalDateTime.now(), null, null);
        ProductRecord record = new ProductRecord(file, LocalDateTime.now(), sku,
                sku.contains("-") ? sku.substring(0, sku.indexOf('-')) : sku,
                "Apparel", null, null, name, "PINKSIR", null, "[]", null, "[]",
                color, null, null, null, null, null, null, null, raw);
        record.setSourceRowNumber(rowNumber);
        return record;
    }
}
