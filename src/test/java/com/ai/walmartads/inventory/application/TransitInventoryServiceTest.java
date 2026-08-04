package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import com.ai.walmartads.importing.domain.ShipmentRecord;
import com.ai.walmartads.importing.repository.ShipmentLogisticsRepository;
import com.ai.walmartads.importing.repository.ShipmentRecordRepository;
import com.ai.walmartads.inventory.domain.TransitChannelLeadTime;
import com.ai.walmartads.inventory.repository.TransitChannelLeadTimeRepository;
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
        when(shipment.getReceiptDifference()).thenReturn(BigDecimal.TEN);
        when(logistics.getShipmentNo()).thenReturn("SHP-001");
        when(logistics.getChannelName()).thenReturn("海运渠道 A");
        when(shipmentRepository.findCurrentVersions()).thenReturn(List.of(shipment));
        when(logisticsRepository.findCurrentVersions()).thenReturn(List.of(logistics));
        when(leadTimeRepository.findAll()).thenReturn(List.of(
                new TransitChannelLeadTime("海运渠道 A", BigDecimal.valueOf(30), false)
        ));

        TransitInventoryService.TransitData result = new TransitInventoryService(
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

        new TransitInventoryService(
                shipmentRepository,
                logisticsRepository,
                leadTimeRepository
        ).syncChannelsForManualMaintenance();

        ArgumentCaptor<TransitChannelLeadTime> captor = ArgumentCaptor.forClass(TransitChannelLeadTime.class);
        verify(leadTimeRepository).save(captor.capture());
        assertThat(captor.getValue().getChannelName()).isEqualTo("渠道 B");
        assertThat(captor.getValue().getLeadDays()).isNull();
        assertThat(captor.getValue().isManual()).isFalse();
    }
}
