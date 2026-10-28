package com.ai.walmartads.importing.domain;

import com.ai.walmartads.importing.application.ProductImportPlanner;
import com.ai.walmartads.workspace.WorkspaceOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Persistent audit detail for one confirmed product-catalog import. */
@Entity
@Table(name = "wm_ads_product_import_change")
public class ProductImportChange extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    private ImportFile importFile;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(nullable = false, length = 180)
    private String sku;

    @Column(length = 80)
    private String parentSku;

    @Column(columnDefinition = "text")
    private String productName;

    @Column(length = 600)
    private String color;

    @Column(length = 120)
    private String size;

    @Column(nullable = false)
    private boolean restored;

    @Column(nullable = false)
    private boolean wholeStyle;

    @Column(nullable = false, columnDefinition = "json")
    private String fieldChanges;

    @Column(nullable = false, length = 180)
    private String operatedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected ProductImportChange() {
    }

    public ProductImportChange(
            ImportFile importFile,
            ProductImportPlanner.PlannedChange change,
            String fieldChanges,
            String operatedBy
    ) {
        this.importFile = importFile;
        this.action = change.type().name();
        this.sku = change.sku();
        this.parentSku = change.parentSku();
        this.productName = change.productName();
        this.color = change.color();
        this.size = change.size();
        this.restored = change.restored();
        this.wholeStyle = change.wholeStyle();
        this.fieldChanges = fieldChanges;
        this.operatedBy = operatedBy == null || operatedBy.isBlank() ? "unknown" : operatedBy;
        this.createdAt = LocalDateTime.now();
    }
}
