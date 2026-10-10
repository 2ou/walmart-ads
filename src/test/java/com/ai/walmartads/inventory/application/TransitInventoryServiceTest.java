package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import com.ai.walmartads.importing.domain.ShipmentRecord;
import com.ai.walmartads.importing.repository.ShipmentLogisticsRepository;
import com.ai.walmartads.importing.repository.ShipmentRecordRepository;
import com.ai.walmartads.inventory.domain.TransitChannelLeadTime;
import com.ai.walmartads.inventory.domain.TransitShipmentEta;
import com.ai.walmartads.inventory.repository.TransitChannelLeadTimeRepository;
import com.ai.walmartads.inventory.repository.TransitShipmentEtaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransitInventoryServiceTest {

    private static TransitInventoryService service(
            ShipmentRecordRepository shipmentRepository,
            ShipmentLogisticsRepository logisticsRepository,
            TransitChannelLeadTimeRepository leadTimeRepository
    ) {
        return new TransitInventoryService(
                shipmentRepository,
                logisticsRepository,
                leadTimeRepository,
                mock(TransitShipmentEtaRepository.class)
        );
    }

    @Test
    void estimatesDepartureAsTheNextWednesdayAfterShipmentCreation() {
        assertThat(TransitInventoryService.estimatedDeparture(LocalDateTime.of(2026, 7, 22, 18, 4)))
                .isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(TransitInventoryService.estimatedDeparture(LocalDateTime.of(2026, 7, 25, 10, 55)))
                .isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void ignoresHistoricalLeadTimeUntilTheChannelIsManuallyMaintained() {
        ShipmentRecordRepository shipmentRepository = mock(ShipmentRecordRepository.class);
        ShipmentLogisticsRepository logisticsRepository = mock(ShipmentLogisticsRepository.class);
        TransitChannelLeadTimeRepository leadTimeRepository = mock(TransitChannelLeadTimeRepository.class);
        ShipmentRecord shipment = mock(ShipmentRecord.class);
        ShipmentLogisticsRecord logistics = mock(ShipmentLogisticsRecord.class);

        when(shipment.getShipmentNo()).thenReturn("SHP-001");
        when(shipment.getLocalStatus()).thenReturn("已发货");
        when(shipment.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(shipment.getSku()).thenReturn("SKU-001");
        when(shipment.getParentSku()).thenReturn("PNK001");
        when(shipment.getReceiptDifference()).thenReturn(BigDecimal.valueOf(100));
        when(logistics.getShipmentNo()).thenReturn("SHP-001");
        when(logistics.getChannelName()).thenReturn("海运渠道 A");
        when(shipmentRepository.findCurrentVersions()).thenReturn(List.of(shipment));
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of(logistics));
        when(leadTimeRepository.findAll()).thenReturn(List.of(
                new TransitChannelLeadTime("海运渠道 A", BigDecimal.valueOf(30), false)
        ));

        TransitInventoryService.TransitData result = service(
                shipmentRepository,
                logisticsRepository,
                leadTimeRepository
        ).load();

        assertThat(result.shipments()).singleElement().satisfies(view -> {
            assertThat(view.leadDays()).isNull();
            assertThat(view.estimatedArrival()).isNull();
            assertThat(view.leadTimeSource()).isEqualTo("NEEDS_MANUAL");
        });
        assertThat(result.channels()).singleElement().satisfies(channel -> {
            assertThat(channel.leadDays()).isNull();
            assertThat(channel.source()).isEqualTo("NEEDS_MANUAL");
        });
    }

    @Test
    void registersNewLogisticsChannelsWithoutGeneratingLeadDays() {
        ShipmentRecordRepository shipmentRepository = mock(ShipmentRecordRepository.class);
        ShipmentLogisticsRepository logisticsRepository = mock(ShipmentLogisticsRepository.class);
        TransitChannelLeadTimeRepository leadTimeRepository = mock(TransitChannelLeadTimeRepository.class);
        ShipmentLogisticsRecord existingChannel = mock(ShipmentLogisticsRecord.class);
        ShipmentLogisticsRecord newChannel = mock(ShipmentLogisticsRecord.class);
        when(existingChannel.getChannelName()).thenReturn("渠道 A");
        when(newChannel.getChannelName()).thenReturn("渠道 B");
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of(existingChannel, newChannel));
        when(leadTimeRepository.findAll()).thenReturn(List.of(
                new TransitChannelLeadTime("渠道 A", BigDecimal.valueOf(25), true)
        ));

        service(shipmentRepository, logisticsRepository, leadTimeRepository).syncChannelsForManualMaintenance();

        ArgumentCaptor<TransitChannelLeadTime> captor = ArgumentCaptor.forClass(TransitChannelLeadTime.class);
        verify(leadTimeRepository).save(captor.capture());
        assertThat(captor.getValue().getChannelName()).isEqualTo("渠道 B");
        assertThat(captor.getValue().getLeadDays()).isNull();
        assertThat(captor.getValue().isManual()).isFalse();
    }

    @Test
    void keepsSmallShipmentsAndOnlyDropsZeroUnitShipments() {
        ShipmentRecordRepository shipmentRepository = mock(ShipmentRecordRepository.class);
        ShipmentLogisticsRepository logisticsRepository = mock(ShipmentLogisticsRepository.class);
        TransitChannelLeadTimeRepository leadTimeRepository = mock(TransitChannelLeadTimeRepository.class);
        ShipmentRecord small = mock(ShipmentRecord.class);
        ShipmentRecord large = mock(ShipmentRecord.class);
        ShipmentRecord empty = mock(ShipmentRecord.class);

        when(small.getShipmentNo()).thenReturn("SHP-SMALL");
        when(small.getLocalStatus()).thenReturn("已发货");
        when(small.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(small.getSku()).thenReturn("SKU-SMALL");
        when(small.getParentSku()).thenReturn("PNK-SMALL");
        when(small.getReceiptDifference()).thenReturn(BigDecimal.valueOf(30)); // 碎片小货件，2026-10-06 起应保留

        when(large.getShipmentNo()).thenReturn("SHP-LARGE");
        when(large.getLocalStatus()).thenReturn("已发货");
        when(large.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(large.getSku()).thenReturn("SKU-LARGE");
        when(large.getParentSku()).thenReturn("PNK-LARGE");
        when(large.getReceiptDifference()).thenReturn(BigDecimal.valueOf(200));

        when(empty.getShipmentNo()).thenReturn("SHP-EMPTY");
        when(empty.getLocalStatus()).thenReturn("已发货");
        when(empty.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(empty.getSku()).thenReturn("SKU-EMPTY");
        when(empty.getParentSku()).thenReturn("PNK-EMPTY");
        when(empty.getReceiptDifference()).thenReturn(BigDecimal.valueOf(0)); // 合计 0 件，仍不应进表

        when(shipmentRepository.findCurrentVersions()).thenReturn(List.of(small, large, empty));
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of());
        when(leadTimeRepository.findAll()).thenReturn(List.of());

        TransitInventoryService.TransitData result = service(
                shipmentRepository,
                logisticsRepository,
                leadTimeRepository
        ).load();

        assertThat(result.shipments()).extracting(TransitInventoryService.TransitShipment::shipmentNo)
                .containsExactlyInAnyOrder("SHP-SMALL", "SHP-LARGE");
        assertThat(result.unitsBySku()).containsEntry("SKU-SMALL", 30.0);
        assertThat(result.unitsBySku()).containsEntry("SKU-LARGE", 200.0);
        assertThat(result.unitsBySku()).doesNotContainKey("SKU-EMPTY");
    }

    /**
     * 货件明细的四个数量必须取源表原值：不归零、不取绝对值。
     *
     * <p>同时守住两条口径的分界线 —— {@code units}（库存总览/补货/预测用）仍是负数归零后的口径，
     * {@code rawTransitUnits}（明细表用）是源表原值，两者刻意不相等。
     */
    @Test
    void readsDeclaredShippedSignedAndTransitUnitsFromRawSnapshotKeepingNegatives() {
        ShipmentRecordRepository shipmentRepository = mock(ShipmentRecordRepository.class);
        ShipmentLogisticsRepository logisticsRepository = mock(ShipmentLogisticsRepository.class);
        TransitChannelLeadTimeRepository leadTimeRepository = mock(TransitChannelLeadTimeRepository.class);
        ShipmentRecord first = mock(ShipmentRecord.class);
        ShipmentRecord second = mock(ShipmentRecord.class);

        when(first.getShipmentNo()).thenReturn("SHP-RAW");
        when(first.getLocalStatus()).thenReturn("已发货");
        when(first.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(first.getSku()).thenReturn("SKU-A");
        when(first.getParentSku()).thenReturn("PNK001");
        when(first.getReceiptDifference()).thenReturn(BigDecimal.valueOf(10));
        when(first.getRawPayload()).thenReturn(
                "{\"申报量\":\"10\",\"已发货\":\"10\",\"签收量(损坏)\":\"0(0)\",\"申收差异\":\"10\"}");

        when(second.getShipmentNo()).thenReturn("SHP-RAW");
        when(second.getLocalStatus()).thenReturn("已发货");
        when(second.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(second.getSku()).thenReturn("SKU-B");
        when(second.getParentSku()).thenReturn("PNK001");
        when(second.getReceiptDifference()).thenReturn(BigDecimal.ZERO); // 源表 -3 在实体列里已被归零
        when(second.getRawPayload()).thenReturn(
                "{\"申报量\":\"5\",\"已发货\":\"5\",\"签收量(损坏)\":\"8(1)\",\"申收差异\":\"-3\"}");

        when(shipmentRepository.findCurrentVersions()).thenReturn(List.of(first, second));
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of());
        when(leadTimeRepository.findAll()).thenReturn(List.of());

        TransitInventoryService.TransitData result = service(
                shipmentRepository,
                logisticsRepository,
                leadTimeRepository
        ).load();

        assertThat(result.shipments()).singleElement().satisfies(view -> {
            assertThat(view.units()).isEqualTo(10.0);            // 归零口径：库存总览仍用它
            assertThat(view.declaredUnits()).isEqualTo(15.0);     // 10 + 5
            assertThat(view.shippedUnits()).isEqualTo(15.0);      // 10 + 5（状态位）
            assertThat(view.signedUnits()).isEqualTo(8.0);        // 0 + 8，只取括号前的签收量
            assertThat(view.rawTransitUnits()).isEqualTo(7.0);    // 10 + (-3)，超收不归零
            assertThat(view.overUnits()).isEqualTo(3.0);          // 仅 -3 那行的绝对值
            assertThat(view.skuCount()).isEqualTo(2);
            assertThat(view.items()).hasSize(2);
            assertThat(view.items().get(1).sku()).isEqualTo("SKU-B");
            assertThat(view.items().get(1).transitUnits()).isEqualTo(-3.0);
            assertThat(view.items().get(1).signedUnits()).isEqualTo(8.0);
        });
        assertThat(result.unitsBySku()).containsEntry("SKU-A", 10.0);
    }

    /** 原始快照缺失（历史数据）时四个数量按 0 计，不能因单行脏数据打断整表。 */
    @Test
    void fallsBackToZeroWhenRawSnapshotIsMissing() {
        ShipmentRecordRepository shipmentRepository = mock(ShipmentRecordRepository.class);
        ShipmentLogisticsRepository logisticsRepository = mock(ShipmentLogisticsRepository.class);
        TransitChannelLeadTimeRepository leadTimeRepository = mock(TransitChannelLeadTimeRepository.class);
        ShipmentRecord legacy = mock(ShipmentRecord.class);

        when(legacy.getShipmentNo()).thenReturn("SHP-LEGACY");
        when(legacy.getLocalStatus()).thenReturn("入库中");
        when(legacy.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(legacy.getSku()).thenReturn("SKU-LEGACY");
        when(legacy.getParentSku()).thenReturn("PNK-LEGACY");
        when(legacy.getReceiptDifference()).thenReturn(BigDecimal.valueOf(42));
        when(legacy.getRawPayload()).thenReturn(null);

        when(shipmentRepository.findCurrentVersions()).thenReturn(List.of(legacy));
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of());
        when(leadTimeRepository.findAll()).thenReturn(List.of());

        TransitInventoryService.TransitData result = service(
                shipmentRepository,
                logisticsRepository,
                leadTimeRepository
        ).load();

        assertThat(result.shipments()).singleElement().satisfies(view -> {
            assertThat(view.units()).isEqualTo(42.0);
            assertThat(view.declaredUnits()).isZero();
            assertThat(view.shippedUnits()).isZero();
            assertThat(view.signedUnits()).isZero();
            assertThat(view.rawTransitUnits()).isZero();
            assertThat(view.overUnits()).isZero();
        });
    }

    /**
     * 「已完成」批次的隔离：它们进「已完成」视图与对账列，但绝不进 unitsBySku（在途口径）。
     */
    @Test
    void closedShipmentsGoToCompletedViewAndNeverIntoTransitUnits() {
        ShipmentRecordRepository shipmentRepository = mock(ShipmentRecordRepository.class);
        ShipmentLogisticsRepository logisticsRepository = mock(ShipmentLogisticsRepository.class);
        TransitChannelLeadTimeRepository leadTimeRepository = mock(TransitChannelLeadTimeRepository.class);
        TransitShipmentEtaRepository etaRepository = mock(TransitShipmentEtaRepository.class);
        ShipmentRecord live = mock(ShipmentRecord.class);
        ShipmentRecord closed = mock(ShipmentRecord.class);

        when(live.getShipmentNo()).thenReturn("SHP-LIVE");
        when(live.getLocalStatus()).thenReturn("已发货");
        when(live.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(live.getSku()).thenReturn("SKU-LIVE");
        when(live.getParentSku()).thenReturn("PNK001");
        when(live.getReceiptDifference()).thenReturn(BigDecimal.valueOf(100));
        when(live.getRawPayload()).thenReturn(
                "{\"申报量\":\"100\",\"已发货\":\"100\",\"签收量(损坏)\":\"0(0)\",\"申收差异\":\"100\"}");

        when(closed.getShipmentNo()).thenReturn("SHP-CLOSED");
        when(closed.getLocalStatus()).thenReturn("已完成");
        when(closed.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 8, 1, 9, 0));
        when(closed.getSku()).thenReturn("SKU-CLOSED");
        when(closed.getParentSku()).thenReturn("PNK002");
        when(closed.getReceiptDifference()).thenReturn(BigDecimal.valueOf(512));
        when(closed.getRawPayload()).thenReturn(
                "{\"申报量\":\"512\",\"已发货\":\"512\",\"签收量(损坏)\":\"0(0)\","
                        + "\"申收差异\":\"512\",\"更新时间\":\"2026-10-09 12:26\"}");

        when(shipmentRepository.findCurrentVersions()).thenReturn(List.of(live, closed));
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of());
        when(leadTimeRepository.findAll()).thenReturn(List.of());
        when(etaRepository.findAll()).thenReturn(List.of());

        TransitInventoryService.TransitData result = new TransitInventoryService(
                shipmentRepository, logisticsRepository, leadTimeRepository, etaRepository
        ).load();

        assertThat(result.shipments()).extracting(TransitInventoryService.TransitShipment::shipmentNo)
                .containsExactly("SHP-LIVE");
        assertThat(result.unitsBySku()).containsOnlyKeys("SKU-LIVE");   // 已完成不进口径
        assertThat(result.completedShipments()).singleElement().satisfies(view -> {
            assertThat(view.shipmentNo()).isEqualTo("SHP-CLOSED");
            assertThat(view.units()).isEqualTo(512.0);
            assertThat(view.closedAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 12, 26));
            assertThat(view.estimatedArrival()).isNull();
            assertThat(view.manualArrival()).isNull();
        });
    }

    /** 人工补充的到达日期启用后优先于系统推算，用于分桶与倒计时。 */
    @Test
    void manualArrivalOverridesEstimatedArrival() {
        ShipmentRecordRepository shipmentRepository = mock(ShipmentRecordRepository.class);
        ShipmentLogisticsRepository logisticsRepository = mock(ShipmentLogisticsRepository.class);
        TransitChannelLeadTimeRepository leadTimeRepository = mock(TransitChannelLeadTimeRepository.class);
        TransitShipmentEtaRepository etaRepository = mock(TransitShipmentEtaRepository.class);
        ShipmentRecord shipment = mock(ShipmentRecord.class);

        when(shipment.getShipmentNo()).thenReturn("SHP-ETA");
        when(shipment.getLocalStatus()).thenReturn("已发货");
        when(shipment.getShipmentCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 25, 10, 55));
        when(shipment.getSku()).thenReturn("SKU-ETA");
        when(shipment.getParentSku()).thenReturn("PNK003");
        when(shipment.getReceiptDifference()).thenReturn(BigDecimal.valueOf(50));
        when(shipment.getRawPayload()).thenReturn("{\"申报量\":\"50\",\"申收差异\":\"50\"}");

        when(shipmentRepository.findCurrentVersions()).thenReturn(List.of(shipment));
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of());
        when(leadTimeRepository.findAll()).thenReturn(List.of());
        when(etaRepository.findAll()).thenReturn(List.of(
                new TransitShipmentEta("SHP-ETA", LocalDate.of(2026, 10, 18))
        ));

        TransitInventoryService.TransitData result = new TransitInventoryService(
                shipmentRepository, logisticsRepository, leadTimeRepository, etaRepository
        ).load();

        assertThat(result.shipments()).singleElement().satisfies(view -> {
            assertThat(view.estimatedArrival()).isNull();
            assertThat(view.manualArrival()).isEqualTo(LocalDate.of(2026, 10, 18));
            assertThat(view.effectiveArrival()).isEqualTo(LocalDate.of(2026, 10, 18));
        });
    }
}
