package com.ai.walmartads.supplychain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Physical movement from factory to the supply-chain receiving warehouse, not the WFS leg. */
final class SupplyChainDeliveryProgress {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final ZoneId SUPPLY_CHAIN_ZONE = ZoneId.of("Asia/Shanghai");

    enum Stage { PENDING_PLAN, IN_TRANSIT, HISTORICAL_RECEIPT_GAP, RECEIVED, UNVERIFIED, NOT_SHIPPED }

    record Result(Stage stage, BigDecimal quantity, LocalDateTime outboundAt) { }

    private SupplyChainDeliveryProgress() { }

    static Result classify(BigDecimal listedQty, BigDecimal qcReceivedQty,
                           String planFlag, String deliveryStatus, String qcHarvestStatus, String warehouseHarvestStatus,
                           String rawOutboundAt, LocalDateTime snapshotAt, boolean otherReceiptEvidence) {
        BigDecimal listed = listedQty.max(ZERO);
        BigDecimal received = qcReceivedQty.max(ZERO);
        BigDecimal gap = listed.subtract(received).max(ZERO);
        String qcState = clean(qcHarvestStatus);
        String warehouseState = clean(warehouseHarvestStatus);
        String outboundText = clean(rawOutboundAt);
        LocalDateTime outboundAt = validOutbound(outboundText, snapshotAt);
        boolean qcComplete = "1".equals(qcState);
        boolean warehouseComplete = "1".equals(warehouseState);
        boolean terminalStatus = "3".equals(clean(deliveryStatus));
        if (listed.signum() == 0) {
            return new Result(received.signum() > 0 || otherReceiptEvidence || outboundText != null
                    ? Stage.UNVERIFIED : Stage.NOT_SHIPPED,
                    ZERO, outboundAt);
        }
        if (gap.signum() == 0) return new Result(Stage.RECEIVED, listed, outboundAt);
        // A closed receipt with an outstanding difference is a historical discrepancy, not current transit.
        if (qcComplete && warehouseComplete) return new Result(Stage.HISTORICAL_RECEIPT_GAP, gap, outboundAt);
        if (qcComplete || warehouseComplete || terminalStatus) return new Result(Stage.UNVERIFIED, gap, outboundAt);
        // Only this delivery detail's own physical outbound event plus explicit open receipt supports transit.
        // A sales plan flag or another transport leg's timestamp is not outbound evidence.
        if (outboundAt != null && "0".equals(qcState) && "0".equals(warehouseState)) {
            return new Result(Stage.IN_TRANSIT, gap, outboundAt);
        }
        String planState = clean(planFlag);
        if (("0".equals(planState) || "未做".equals(planState)) && outboundText == null
                && received.signum() == 0 && !otherReceiptEvidence) {
            return new Result(Stage.PENDING_PLAN, listed, null);
        }
        return new Result(Stage.UNVERIFIED, gap, outboundAt);
    }

    private static LocalDateTime validOutbound(String value, LocalDateTime snapshotAt) {
        if (value == null || snapshotAt == null) return null;
        String normalized = value.replace(' ', 'T');
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            try {
                parsed = OffsetDateTime.parse(normalized).atZoneSameInstant(SUPPLY_CHAIN_ZONE).toLocalDateTime();
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
        return parsed.getYear() >= 2000 && !parsed.isAfter(snapshotAt) ? parsed : null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }
}
