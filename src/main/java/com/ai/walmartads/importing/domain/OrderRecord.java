package com.ai.walmartads.importing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_order_record")
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    private ImportFile importFile;

    @Column(nullable = false)
    private LocalDate reportDate;

    @Column(nullable = false)
    private LocalDate orderDate;

    @Column(length = 80)
    private String poNumber;

    @Column(nullable = false, length = 80)
    private String orderNumber;

    @Column(length = 40)
    private String lineNumber;

    @Column(nullable = false, length = 160)
    private String sku;

    @Column(length = 80)
    private String parentSku;

    @Column(length = 40)
    private String upc;

    @Column(length = 80)
    private String status;

    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 4)
    private BigDecimal itemCost;

    @Column(precision = 18, scale = 4)
    private BigDecimal originalPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal shippingCost;

    @Column(columnDefinition = "text")
    private String itemDescription;

    @Column(length = 80)
    private String shippingMethod;

    @Column(length = 80)
    private String fulfillmentType;

    @Column(nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected OrderRecord() {
    }

    public OrderRecord(
            ImportFile importFile,
            LocalDate reportDate,
            LocalDate orderDate,
            String poNumber,
            String orderNumber,
            String lineNumber,
            String sku,
            String parentSku,
            String upc,
            String status,
            BigDecimal quantity,
            BigDecimal itemCost,
            BigDecimal originalPrice,
            BigDecimal shippingCost,
            String itemDescription,
            String shippingMethod,
            String fulfillmentType,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.reportDate = reportDate;
        this.orderDate = orderDate;
        this.poNumber = poNumber;
        this.orderNumber = orderNumber;
        this.lineNumber = lineNumber;
        this.sku = sku;
        this.parentSku = parentSku;
        this.upc = upc;
        this.status = status;
        this.quantity = quantity;
        this.itemCost = itemCost;
        this.originalPrice = originalPrice;
        this.shippingCost = shippingCost;
        this.itemDescription = itemDescription;
        this.shippingMethod = shippingMethod;
        this.fulfillmentType = fulfillmentType;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }
}
