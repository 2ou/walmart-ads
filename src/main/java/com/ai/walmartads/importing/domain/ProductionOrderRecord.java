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

/**
 * 生产订单（做货）记录。数据源为「生产中订单」Excel（source/生产订单/*.xlsx）。
 * 仅放一个文件，每次扫描以最新导入为准：重新导入时先作废全部旧版本，再写入新版本（整类型作废）。
 *
 * 做货数量规则：裁床数 > 0 时取裁床数，否则取开单数，存为派生列 productionQty。
 */
@Entity
@Table(name = "wm_ads_production_order")
public class ProductionOrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    /** 关联导入文件ID */
    private ImportFile importFile;

    @Column(nullable = false)
    /** 是否当前版本（1是/0否） */
    private boolean currentVersion;

    @Column(nullable = false, length = 180)
    /** 商品 SKU（生产订单唯一键） */
    private String sku;

    @Column(length = 120)
    /** 款号 */
    private String styleNo;

    @Column(length = 120)
    /** 订单编号 */
    private String orderNo;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(length = 60)
    /** 型号（如 M / L / XL） */
    private String model;

    @Column(precision = 18, scale = 4)
    /** 开单数（件） */
    private BigDecimal openQty;

    @Column(precision = 18, scale = 4)
    /** 裁床数（件） */
    private BigDecimal cuttingQty;

    @Column(precision = 18, scale = 4)
    /** 做货数量（派生：裁床数>0 取裁床数，否则开单数） */
    private BigDecimal productionQty;

    @Column(precision = 18, scale = 4)
    /** 发货数（件） */
    private BigDecimal shipQty;

    @Column(precision = 18, scale = 4)
    /** 仓库收货数（件） */
    private BigDecimal warehouseReceiveQty;

    @Column(precision = 18, scale = 4)
    /** 质检合格数（件） */
    private BigDecimal qcPassQty;

    @Column(precision = 18, scale = 4)
    /** 未完成数（件） */
    private BigDecimal unfinishedQty;

    @Column(length = 60)
    /** 工厂确认回货日期 */
    private String factoryReturnDate;

    @Column(length = 120)
    /** 所属店铺 */
    private String store;

    @Column(length = 120)
    /** 订单类型 */
    private String orderType;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected ProductionOrderRecord() {
    }

    public ProductionOrderRecord(
            ImportFile importFile,
            String sku,
            String styleNo,
            String orderNo,
            LocalDate orderDate,
            String model,
            BigDecimal openQty,
            BigDecimal cuttingQty,
            BigDecimal productionQty,
            BigDecimal shipQty,
            BigDecimal warehouseReceiveQty,
            BigDecimal qcPassQty,
            BigDecimal unfinishedQty,
            String factoryReturnDate,
            String store,
            String orderType,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.currentVersion = true;
        this.sku = sku;
        this.styleNo = styleNo;
        this.orderNo = orderNo;
        this.orderDate = orderDate;
        this.model = model;
        this.openQty = openQty;
        this.cuttingQty = cuttingQty;
        this.productionQty = productionQty;
        this.shipQty = shipQty;
        this.warehouseReceiveQty = warehouseReceiveQty;
        this.qcPassQty = qcPassQty;
        this.unfinishedQty = unfinishedQty;
        this.factoryReturnDate = factoryReturnDate;
        this.store = store;
        this.orderType = orderType;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getStyleNo() {
        return styleNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public String getModel() {
        return model;
    }

    public BigDecimal getOpenQty() {
        return openQty;
    }

    public BigDecimal getCuttingQty() {
        return cuttingQty;
    }

    public BigDecimal getProductionQty() {
        return productionQty;
    }

    public BigDecimal getShipQty() {
        return shipQty;
    }

    public BigDecimal getWarehouseReceiveQty() {
        return warehouseReceiveQty;
    }

    public BigDecimal getQcPassQty() {
        return qcPassQty;
    }

    public BigDecimal getUnfinishedQty() {
        return unfinishedQty;
    }

    public String getFactoryReturnDate() {
        return factoryReturnDate;
    }

    public String getStore() {
        return store;
    }

    public String getOrderType() {
        return orderType;
    }

    public String getRawPayload() {
        return rawPayload;
    }
}
