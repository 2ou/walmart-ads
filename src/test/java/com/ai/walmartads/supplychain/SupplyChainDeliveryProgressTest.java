package com.ai.walmartads.supplychain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.ai.walmartads.supplychain.SupplyChainDeliveryProgress.Stage.*;
import static org.assertj.core.api.Assertions.assertThat;

class SupplyChainDeliveryProgressTest {
    private static final LocalDateTime SNAPSHOT = LocalDateTime.parse("2026-11-06T17:45:46");

    @Test
    void splitsListedButUnshippedAndClosedReceiptGapInsteadOfReporting902InTransit() {
        var pending = classify("854", "0", "0", "1", "0", "0", null);
        var historical = classify("148", "100", "1", "3", "1", "1", null);
        var alreadyReceived = classify("120", "120", "1", "2", "1", "1", null);

        assertThat(pending.stage()).isEqualTo(PENDING_PLAN);
        assertThat(historical.stage()).isEqualTo(HISTORICAL_RECEIPT_GAP);
        assertThat(alreadyReceived.stage()).isEqualTo(RECEIVED);

        var summary = SupplyChainWorkspaceService.summarize(List.of(), List.of(), List.of(
                delivery("D-1", "854", "0", "0", pending),
                delivery("D-2", "148", "100", "2", historical),
                delivery("D-3", "120", "120", "0", alreadyReceived)), List.of(), List.of(
                workbench("SKU-1", 976, true), workbench("SKU-UNKNOWN", 88, false)), true);
        assertThat(summary.pendingPlanQty()).isEqualByComparingTo("854");
        assertThat(summary.historicalReceiptGapQty()).isEqualByComparingTo("48");
        assertThat(summary.inTransitQty()).isZero();
        assertThat(summary.unverifiedQty()).isZero();
        assertThat(summary.reworkQty()).isEqualByComparingTo("2");
        assertThat(summary.workbenchWipQty()).isEqualByComparingTo("976");
        assertThat(summary.workbenchWipUnmatchedQty()).isEqualByComparingTo("88");
        assertThat(summary.workbenchWipTotalQty()).isEqualByComparingTo("1064");
        assertThat(summary.workbenchWipSource()).isEqualTo("SUPPLY_CHAIN");
    }

    @Test
    void genuineOutboundAndOpenReceiptCountOnlyOutstandingUnits() {
        var progress = classify("100", "60", "1", "2", "0", "0", "2026-11-06 08:32:15");
        assertThat(progress.stage()).isEqualTo(IN_TRANSIT);
        assertThat(progress.quantity()).isEqualByComparingTo("40");
        assertThat(progress.outboundAt()).isEqualTo(LocalDateTime.parse("2026-11-06T08:32:15"));
        assertThat(classify("100", "60", "0", "2", "0", "0", "2026-11-06T00:32:15Z").stage())
                .isEqualTo(IN_TRANSIT); // planning for the next leg does not disprove this leg's outbound evidence
    }

    @Test
    void planOrPartialReceiptWithoutOutboundEvidenceDoesNotConfirmTransit() {
        assertThat(classify("90", "0", "1", "2", "0", "0", null).stage()).isEqualTo(UNVERIFIED);
        assertThat(classify("90", "15", "0", "2", "0", "0", null).stage()).isEqualTo(UNVERIFIED);
        assertThat(classify("90", "0", "", "2", "0", "0", null).stage()).isEqualTo(UNVERIFIED);
        assertThat(classify("90", "0", "未做", "1", "0", "0", null).stage()).isEqualTo(PENDING_PLAN);
        assertThat(SupplyChainDeliveryProgress.classify(BigDecimal.TEN, BigDecimal.ZERO,
                "0", "1", "0", "0", null, SNAPSHOT, true).stage()).isEqualTo(UNVERIFIED);
    }

    @Test
    void futureMalformedOrUncorroboratedEventsStayUnverified() {
        for (String timestamp : List.of("2026-11-07T12:00:00", "not an outbound event")) {
            var progress = classify("90", "0", "0", "1", "0", "0", timestamp);
            assertThat(progress.stage()).isEqualTo(UNVERIFIED);
            assertThat(progress.outboundAt()).isNull();
        }
        var rawTerminalOnly = classify("90", "15", "1", "3", "0", "0", "2026-11-06T08:00:00");
        assertThat(rawTerminalOnly.stage()).isEqualTo(UNVERIFIED);
        assertThat(classify("90", "15", "1", "2", "1", "0", "2026-11-06T08:00:00").stage())
                .isEqualTo(UNVERIFIED);
    }

    @Test
    void receiptCanBeConfirmedWithoutOutboundTimestampAndDoesNotOverstateListedUnits() {
        var overReceived = classify("36", "39", "1", "2", "1", "1", null);
        assertThat(overReceived.stage()).isEqualTo(RECEIVED);
        assertThat(overReceived.quantity()).isEqualByComparingTo("36");
        assertThat(classify("0", "0", "0", "1", "0", "0", null).stage()).isEqualTo(NOT_SHIPPED);
        assertThat(classify("0", "1", "0", "1", "0", "0", null).stage()).isEqualTo(UNVERIFIED);
    }

    @Test
    void aSuccessfulEmptySupplySnapshotIsARealZero() {
        var empty = SupplyChainWorkspaceService.summarize(List.of(), List.of(), List.of(), List.of(), List.of(), true);
        assertThat(empty.workbenchWipQty()).isZero();
        assertThat(empty.workbenchWipSource()).isEqualTo("SUPPLY_CHAIN");
        var notConfigured = SupplyChainWorkspaceService.summarize(List.of(), List.of(), List.of(), List.of(), List.of(), false);
        assertThat(notConfigured.workbenchWipSource()).isNull();
    }

    private static SupplyChainDeliveryProgress.Result classify(String listed, String qc, String plan, String status,
                                                               String qcStatus, String warehouseStatus, String outbound) {
        return SupplyChainDeliveryProgress.classify(new BigDecimal(listed), new BigDecimal(qc), plan, status,
                qcStatus, warehouseStatus, outbound, SNAPSHOT, false);
    }

    private static SupplyChainWorkspaceService.DeliveryRow delivery(String deliveryNo, String listed, String qc,
                                                                     String rework, SupplyChainDeliveryProgress.Result result) {
        return new SupplyChainWorkspaceService.DeliveryRow(deliveryNo, "PO-1", "STYLE", "SKU", null, null,
                BigDecimal.ZERO, new BigDecimal(listed), BigDecimal.ZERO, new BigDecimal(qc), BigDecimal.ZERO,
                new BigDecimal(rework), null, null, null, null, null, null, null, SNAPSHOT,
                result.stage().name(), result.quantity(), result.outboundAt());
    }

    private static SupplyChainWorkspaceService.WorkbenchProductionRow workbench(String sku, double qty, boolean included) {
        return new SupplyChainWorkspaceService.WorkbenchProductionRow("PO-1", sku, "STYLE", qty, null, qty,
                included ? qty : 0, included, null, null, "SUPPLY_CHAIN");
    }
}
