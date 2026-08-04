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
import java.util.Map;

@Entity
@Table(name = "wm_ads_return_order")
public class ReturnOrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    /** 关联导入文件ID */
    private ImportFile importFile;

    @Column(nullable = false)
    /** 源数据行号 */
    private int sourceRowNumber;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    /** 业务唯一键（sha256，用于去重） */
    private String businessKey;

    @Column(nullable = false)
    /** 报表月份 */
    private LocalDate reportMonth;

    @Column(nullable = false)
    /** 退货申请时间 */
    private LocalDateTime requestAt;

    @Column(nullable = false)
    /** 退货申请日期 */
    private LocalDate requestDate;

    @Column(nullable = false, length = 80)
    /** 客户订单号 */
    private String customerOrderNo;

    @Column(length = 80)
    /** 采购单号（可空） */
    private String poNo;

    @Column(length = 40)
    /** 采购单行号（可空） */
    private String poLineNo;

    @Column(nullable = false, length = 100)
    /** RMA 号 */
    private String rmaNo;

    @Column(length = 40)
    /** 退货单行号（可空） */
    private String returnOrderLineNo;

    @Column(nullable = false, precision = 18, scale = 4)
    /** 数量 */
    private BigDecimal quantity;

    @Column(length = 300)
    /** 退货原因（可空） */
    private String returnReason;

    @Column(nullable = false, length = 80)
    /** 商品ID */
    private String itemId;

    @Column(length = 180)
    /** 商品 SKU（可空） */
    private String sku;

    @Column(length = 80)
    /** 父 SKU（可空） */
    private String parentSku;

    @Column(name = "condition_name", length = 80)
    /** 商品状态（可空） */
    private String itemCondition;

    @Column(length = 100)
    /** 当前状态（可空） */
    private String currentStatus;

    /** 当前状态时间（可空） */
    private LocalDateTime currentStatusTime;

    @Column(nullable = false)
    /** 是否保留（1是/0否） */
    private boolean keepIt;

    @Column(columnDefinition = "text")
    /** 商品名称（可空） */
    private String itemName;

    @Column(length = 80)
    /** 承运商（可空） */
    private String carrier;

    @Column(length = 200)
    /** 物流单号（可空） */
    private String trackingNumber;

    @Column(length = 120)
    /** 渠道（可空） */
    private String channel;

    @Column(length = 300)
    /** 客户名称（可空） */
    private String customerName;

    @Column(length = 500)
    /** 客户邮箱（可空） */
    private String customerEmail;

    @Column(columnDefinition = "text")
    /** 客户备注（可空） */
    private String customerComments;

    @Column(nullable = false, precision = 18, scale = 6)
    /** 订单金额 */
    private BigDecimal orderAmount;

    @Column(nullable = false, precision = 18, scale = 6)
    /** 退货运费 */
    private BigDecimal returnShippingFee;

    @Column(nullable = false, precision = 18, scale = 6)
    /** 重新上架费 */
    private BigDecimal restockingFee;

    @Column(nullable = false, precision = 18, scale = 6)
    /** 退款总额 */
    private BigDecimal totalRefundAmount;

    @Column(length = 100)
    /** 卖家订单号（可空） */
    private String sellerOrderNo;

    @Column(length = 100)
    /** 退款方式（可空） */
    private String refundMode;

    @Column(length = 120)
    /** 退款承担方（可空） */
    private String refundCoveredBy;

    @Column(length = 100)
    /** 物流状态（可空） */
    private String trackingStatus;

    @Column(length = 100)
    /** 退款状态（可空） */
    private String refundStatus;

    @Column(nullable = false)
    /** 是否换货（1是/0否） */
    private boolean replacement;

    @Column(length = 80)
    /** 平台类型（可空） */
    private String platformType;

    @Column(length = 120)
    /** 退货发出地（可空） */
    private String returnShipFrom;

    @Column(length = 120)
    /** 退货接收地（可空） */
    private String returnShipTo;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected ReturnOrderRecord() {
    }

    public ReturnOrderRecord(
            ImportFile importFile,
            int sourceRowNumber,
            String businessKey,
            LocalDate reportMonth,
            LocalDateTime requestAt,
            LocalDateTime currentStatusTime,
            String sku,
            String parentSku,
            Map<String, String> values,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.sourceRowNumber = sourceRowNumber;
        this.businessKey = businessKey;
        this.reportMonth = reportMonth;
        this.requestAt = requestAt;
        this.requestDate = requestAt.toLocalDate();
        this.customerOrderNo = required(values, "CUSTOMER_ORDER_NO");
        this.poNo = values.get("PO_NO");
        this.poLineNo = values.get("PO_LINE_NO");
        this.rmaNo = required(values, "RMA_NO");
        this.returnOrderLineNo = values.get("RETURN_ORDER_LINE_NO");
        this.quantity = decimal(values.get("QUANTITY"));
        this.returnReason = values.get("RETURN_REASON");
        this.itemId = required(values, "ITEM_ID");
        this.sku = sku;
        this.parentSku = parentSku;
        this.itemCondition = values.get("CONDITION");
        this.currentStatus = values.get("CURRENT_STATUS");
        this.currentStatusTime = currentStatusTime;
        this.keepIt = bool(values.get("KEEP_IT"));
        this.itemName = values.get("ITEM_NAME");
        this.carrier = values.get("CARRIER");
        this.trackingNumber = values.get("TRACKING_NUMBER");
        this.channel = values.get("CHANNEL");
        this.customerName = values.get("CUSTOMER_NAME");
        this.customerEmail = values.get("CUSTOMER_EMAIL");
        this.customerComments = values.get("CUSTOMER_COMMENTS");
        this.orderAmount = decimal(values.get("ORDER_AMOUNT"));
        this.returnShippingFee = decimal(values.get("RETURN_SHIPPING_FEE"));
        this.restockingFee = decimal(values.get("RESTOCKING_FEE"));
        this.totalRefundAmount = decimal(values.get("TOTAL_REFUND_AMOUNT"));
        this.sellerOrderNo = values.get("SELLER_ORDER_NO");
        this.refundMode = values.get("REFUND_MODE");
        this.refundCoveredBy = values.get("REFUND_COVERED_BY");
        this.trackingStatus = values.get("TRACKING_STATUS");
        this.refundStatus = values.get("REFUND_STATUS");
        this.replacement = bool(values.get("REPLACEMENT"));
        this.platformType = values.get("PLATFORM_TYPE");
        this.returnShipFrom = values.get("RETURN_SHIP_FROM");
        this.returnShipTo = values.get("RETURN_SHIP_TO");
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public LocalDate getReportMonth() {
        return reportMonth;
    }

    public LocalDateTime getRequestAt() {
        return requestAt;
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("退款报表字段为空：" + name);
        }
        return value;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        String normalized = value.trim().replace("$", "").replace(",", "");
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean bool(String value) {
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()));
    }
}
