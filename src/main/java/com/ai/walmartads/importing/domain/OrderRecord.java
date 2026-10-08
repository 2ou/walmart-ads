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
public class OrderRecord extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    /** 关联导入文件ID */
    private ImportFile importFile;

    @Column(nullable = false)
    /** 报表日期 */
    private LocalDate reportDate;

    @Column(nullable = false)
    /** 订单日期 */
    private LocalDate orderDate;

    @Column(length = 80)
    /** 采购单号（可空） */
    private String poNumber;

    @Column(nullable = false, length = 80)
    /** 订单号 */
    private String orderNumber;

    @Column(length = 40)
    /** 行号（可空） */
    private String lineNumber;

    @Column(nullable = false, length = 160)
    /** 商品 SKU */
    private String sku;

    @Column(length = 80)
    /** 父 SKU（可空） */
    private String parentSku;

    @Column(length = 40)
    /** UPC（可空） */
    private String upc;

    @Column(length = 80)
    /** 状态（可空） */
    private String status;

    @Column(precision = 18, scale = 4)
    /** 数量 */
    private BigDecimal quantity;

    @Column(precision = 18, scale = 4)
    /** 商品成本 */
    private BigDecimal itemCost;

    @Column(precision = 18, scale = 4)
    /** 原价 */
    private BigDecimal originalPrice;

    @Column(precision = 18, scale = 4)
    /** 运费 */
    private BigDecimal shippingCost;

    @Column(columnDefinition = "text")
    /** 商品描述（可空） */
    private String itemDescription;

    @Column(length = 80)
    /** 配送方式（可空） */
    private String shippingMethod;

    @Column(length = 80)
    /** 履约类型（可空） */
    private String fulfillmentType;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
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
