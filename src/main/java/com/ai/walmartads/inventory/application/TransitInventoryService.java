package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import com.ai.walmartads.importing.domain.ShipmentRecord;
import com.ai.walmartads.importing.repository.ShipmentLogisticsRepository;
import com.ai.walmartads.importing.repository.ShipmentRecordRepository;
import com.ai.walmartads.inventory.domain.TransitChannelLeadTime;
import com.ai.walmartads.inventory.repository.TransitChannelLeadTimeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将货件明细转换为路途库存，并用物流登记表关联渠道与货代。
 * 货件的预计开船日固定为创建日期之后的下一个周三；渠道时效全部由用户人工维护。
 */
@Service
public class TransitInventoryService {

    private final ShipmentRecordRepository shipmentRepository;
    private final ShipmentLogisticsRepository logisticsRepository;
    private final TransitChannelLeadTimeRepository channelLeadTimeRepository;

    public TransitInventoryService(
            ShipmentRecordRepository shipmentRepository,
            ShipmentLogisticsRepository logisticsRepository,
            TransitChannelLeadTimeRepository channelLeadTimeRepository
    ) {
        this.shipmentRepository = shipmentRepository;
        this.logisticsRepository = logisticsRepository;
        this.channelLeadTimeRepository = channelLeadTimeRepository;
    }

    /** 导入物流信息登记表后，只登记新渠道，等待用户人工维护时效。 */
    @Transactional
    public void syncChannelsForManualMaintenance() {
        List<String> allChannels = logisticsRepository.findCurrentVersions().stream()
                .map(ShipmentLogisticsRecord::getChannelName)
                .filter(TransitInventoryService::present)
                .distinct()
                .toList();
        Set<String> existing = channelLeadTimeRepository.findAll().stream()
                .map(TransitChannelLeadTime::getChannelName)
                .collect(Collectors.toSet());

        for (String channel : allChannels) {
            if (!existing.contains(channel)) {
                channelLeadTimeRepository.save(new TransitChannelLeadTime(channel, null, false));
            }
        }
    }

    /**
     * 计入在途的货件编号集合。
     * 2026-10-06 起取消原「整单申收差异 ≥ 50 件」门槛：所有真实在途货件都参与货件明细、
     * 按 SKU 路途库存与补货供应的计算，不再把碎片小货件（拆单、尾货、补发）当噪音剔除。
     * 仅过滤申收差异合计为 0 的货件（无实际在途量，进表只会是空行）。
 */
    private Set<String> keptShipmentNos() {
        Map<String, Double> unitsByShipment = new LinkedHashMap<>();
        for (ShipmentRecord record : shipmentRepository.findCurrentVersions()) {
            unitsByShipment.merge(record.getShipmentNo(), nonNegative(record.getReceiptDifference()), Double::sum);
        }
        return unitsByShipment.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional(readOnly = true)
    public TransitData load() {
        Set<String> kept = keptShipmentNos();
        Map<String, ShipmentLogisticsRecord> logisticsByShipment = new LinkedHashMap<>();
        logisticsRepository.findCurrentVersions().forEach(record ->
                logisticsByShipment.putIfAbsent(record.getShipmentNo(), record));
        Map<String, TransitChannelLeadTime> leadTimeByChannel = channelLeadTimeRepository.findAll().stream()
                .collect(Collectors.toMap(TransitChannelLeadTime::getChannelName, value -> value));

        Map<String, MutableShipment> shipments = new LinkedHashMap<>();
        Map<String, Double> unitsBySku = new LinkedHashMap<>();
        for (ShipmentRecord record : shipmentRepository.findCurrentVersions()) {
            double units = nonNegative(record.getReceiptDifference());
            if (kept.contains(record.getShipmentNo())) {
                unitsBySku.merge(record.getSku(), units, Double::sum);
            }
            MutableShipment shipment = shipments.computeIfAbsent(record.getShipmentNo(), key ->
                    new MutableShipment(record.getShipmentNo(), record.getLocalStatus(), record.getShipmentCreatedAt()));
            shipment.units += units;
            shipment.skuCount++;
            String parentSku = present(record.getParentSku()) ? record.getParentSku() : record.getSku();
            shipment.parentSkus.add(parentSku);
        }

        List<TransitShipment> views = new ArrayList<>();
        shipments.values().forEach(shipment -> {
            if (!kept.contains(shipment.shipmentNo)) {
                return;
            }
            ShipmentLogisticsRecord logistics = logisticsByShipment.get(shipment.shipmentNo);
            String channel = logistics == null ? null : logistics.getChannelName();
            TransitChannelLeadTime leadTime = channel == null ? null : leadTimeByChannel.get(channel);
            Double leadDays = leadTime == null || !leadTime.isManual() || leadTime.getLeadDays() == null
                    ? null : leadTime.getLeadDays().doubleValue();
            LocalDate estimatedDeparture = estimatedDeparture(shipment.createdAt);
            LocalDate estimatedArrival = estimatedArrival(estimatedDeparture, leadDays);
            String source = channel == null ? "UNMATCHED" : leadDays == null
                    ? "NEEDS_MANUAL" : "MANUAL";
            views.add(new TransitShipment(
                    shipment.shipmentNo,
                    shipment.localStatus,
                    shipment.createdAt,
                    logistics == null ? null : logistics.getForwarder(),
                    channel,
                    round(shipment.units),
                    shipment.skuCount,
                    List.copyOf(shipment.parentSkus),
                    estimatedDeparture,
                    leadDays,
                    estimatedArrival,
                    source
            ));
        });
        views.sort(Comparator.comparing(TransitShipment::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new TransitData(
                Map.copyOf(unitsBySku),
                views,
                channelLeadTimeRepository.findAll().stream()
                        .map(this::toView)
                        .sorted(Comparator.comparing(TransitChannel::channelName))
                        .toList()
        );
    }

    /** Returns only SKU-level in-transit supply that becomes sellable in the requested horizon. */
    @Transactional(readOnly = true)
    public List<TransitSkuSupply> suppliesAvailableWithin(LocalDate snapshotDate, int horizonDays) {
        if (snapshotDate == null || horizonDays <= 0) {
            return List.of();
        }
        Set<String> kept = keptShipmentNos();
        Map<String, ShipmentLogisticsRecord> logisticsByShipment = new LinkedHashMap<>();
        logisticsRepository.findCurrentVersions().forEach(record ->
                logisticsByShipment.putIfAbsent(record.getShipmentNo(), record));
        Map<String, TransitChannelLeadTime> leadTimeByChannel = channelLeadTimeRepository.findAll().stream()
                .collect(Collectors.toMap(TransitChannelLeadTime::getChannelName, value -> value));
        List<TransitSkuSupply> result = new ArrayList<>();
        for (ShipmentRecord record : shipmentRepository.findCurrentVersions()) {
            if (!kept.contains(record.getShipmentNo())) {
                continue;
            }
            ShipmentLogisticsRecord logistics = logisticsByShipment.get(record.getShipmentNo());
            String channel = logistics == null ? null : logistics.getChannelName();
            TransitChannelLeadTime leadTime = channel == null ? null : leadTimeByChannel.get(channel);
            Double leadDays = leadTime == null || !leadTime.isManual() || leadTime.getLeadDays() == null
                    ? null : leadTime.getLeadDays().doubleValue();
            LocalDate arrival = estimatedArrival(estimatedDeparture(record.getShipmentCreatedAt()), leadDays);
            if (arrival == null || arrival.isBefore(snapshotDate.plusDays(1)) || arrival.isAfter(snapshotDate.plusDays(horizonDays))) {
                continue;
            }
            double units = nonNegative(record.getReceiptDifference());
            if (units <= 0) {
                continue;
            }
            result.add(new TransitSkuSupply(
                    record.getSku(),
                    Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(snapshotDate, arrival)),
                    round(units)
            ));
        }
        return List.copyOf(result);
    }

    @Transactional
    public TransitChannel updateManualLeadDays(String channelName, Double leadDays) {
        if (!present(channelName)) {
            throw new IllegalArgumentException("渠道不能为空");
        }
        if (leadDays == null || !Double.isFinite(leadDays) || leadDays <= 0 || leadDays > 180) {
            throw new IllegalArgumentException("渠道时效必须为 1 到 180 天");
        }
        TransitChannelLeadTime value = channelLeadTimeRepository.findById(channelName)
                .orElseGet(() -> new TransitChannelLeadTime(channelName, null, false));
        value.updateManually(BigDecimal.valueOf(leadDays).setScale(2, RoundingMode.HALF_UP));
        return toView(channelLeadTimeRepository.save(value));
    }

    private TransitChannel toView(TransitChannelLeadTime value) {
        return new TransitChannel(
                value.getChannelName(),
                value.isManual() && value.getLeadDays() != null ? value.getLeadDays().doubleValue() : null,
                value.isManual() && value.getLeadDays() != null ? "MANUAL" : "NEEDS_MANUAL",
                value.getUpdatedAt()
        );
    }

    public static LocalDate estimatedDeparture(LocalDateTime shipmentCreatedAt) {
        return shipmentCreatedAt == null ? null : shipmentCreatedAt.toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
    }

    private static LocalDate estimatedArrival(LocalDate departure, Double leadDays) {
        if (departure == null || leadDays == null) {
            return null;
        }
        return departure.plusDays(Math.round(leadDays));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static double nonNegative(BigDecimal value) {
        return value == null ? 0 : value.max(BigDecimal.ZERO).doubleValue();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static final class MutableShipment {
        private final String shipmentNo;
        private final String localStatus;
        private final LocalDateTime createdAt;
        private double units;
        private int skuCount;
        private final Set<String> parentSkus = new LinkedHashSet<>();

        private MutableShipment(String shipmentNo, String localStatus, LocalDateTime createdAt) {
            this.shipmentNo = shipmentNo;
            this.localStatus = localStatus;
            this.createdAt = createdAt;
        }
    }

    public record TransitData(
            Map<String, Double> unitsBySku,
            List<TransitShipment> shipments,
            List<TransitChannel> channels
    ) {
    }

    public record TransitShipment(
            String shipmentNo,
            String localStatus,
            LocalDateTime createdAt,
            String forwarder,
            String channel,
            double units,
            int skuCount,
            List<String> parentSkus,
            LocalDate estimatedDeparture,
            Double leadDays,
            LocalDate estimatedArrival,
            String leadTimeSource
    ) {
    }

    public record TransitChannel(
            String channelName,
            Double leadDays,
            String source,
            LocalDateTime updatedAt
    ) {
    }

    public record TransitSkuSupply(String sku, int availableDay, double units) {
    }

    public record LeadTimePatch(Double leadDays) {
    }
}
