package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import com.ai.walmartads.importing.domain.ShipmentRecord;
import com.ai.walmartads.importing.repository.ShipmentLogisticsRepository;
import com.ai.walmartads.importing.repository.ShipmentRecordRepository;
import com.ai.walmartads.inventory.domain.TransitChannelLeadTime;
import com.ai.walmartads.inventory.domain.TransitShipmentEta;
import com.ai.walmartads.inventory.repository.TransitChannelLeadTimeRepository;
import com.ai.walmartads.inventory.repository.TransitShipmentEtaRepository;
import com.ai.walmartads.supplychain.LogisticsRegisterSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 将货件明细转换为路途库存，并用物流信息登记关联渠道与货代。
 * 货件的预计开船日固定为创建日期之后的下一个周三；渠道时效全部由用户人工维护。
 *
 * <p>物流信息登记自 2026-10-19 起由「穿针引线供应链」插件同步
 * （{@code wm_ads_supply_logistics_register}），不再读取 Excel 导入表。
 * 老表 {@code wm_ads_shipment_logistics} 仅在插件快照为空时作为兜底，避免首次部署瞬间全部变 UNMATCHED。
 */
@Service
public class TransitInventoryService {

    private static final Logger log = LoggerFactory.getLogger(TransitInventoryService.class);

    /** 货件表源列名：四个数量列 + 更新时间（已完成批次的结案时间）。 */
    private static final String COLUMN_DECLARED = "申报量";
    private static final String COLUMN_SHIPPED = "已发货";
    private static final String COLUMN_SIGNED = "签收量(损坏)";
    private static final String COLUMN_TRANSIT = "申收差异";
    private static final String COLUMN_UPDATED_AT = "更新时间";

    /** 计入路途库存的货件本地状态；「已完成」等已结案批次只进明细视图，不进口径。 */
    private static final Set<String> IN_TRANSIT_STATUSES = Set.of("入库中", "已发货", "已申报");
    /** 已结案状态：进「已完成」视图，但绝不参与在途量、补货与需求预测。 */
    private static final String CLOSED_STATUS = "已完成";

    /** 解析逐一留存的行原始快照用；快照的键即源表表头。 */
    private static final ObjectMapper SNAPSHOT_JSON = new ObjectMapper();

    private final ShipmentRecordRepository shipmentRepository;
    private final ShipmentLogisticsRepository logisticsRepository;
    private final LogisticsRegisterSource logisticsRegisterSource;
    private final TransitChannelLeadTimeRepository channelLeadTimeRepository;
    private final TransitShipmentEtaRepository shipmentEtaRepository;

    public TransitInventoryService(
            ShipmentRecordRepository shipmentRepository,
            ShipmentLogisticsRepository logisticsRepository,
            LogisticsRegisterSource logisticsRegisterSource,
            TransitChannelLeadTimeRepository channelLeadTimeRepository,
            TransitShipmentEtaRepository shipmentEtaRepository
    ) {
        this.shipmentRepository = shipmentRepository;
        this.logisticsRepository = logisticsRepository;
        this.logisticsRegisterSource = logisticsRegisterSource;
        this.channelLeadTimeRepository = channelLeadTimeRepository;
        this.shipmentEtaRepository = shipmentEtaRepository;
    }

    /** 供应链插件同步出新渠道后，登记到「渠道时效维护」等待人工填写时效。 */
    @Transactional
    public void syncChannelsForManualMaintenance() {
        Set<String> allChannels = new LinkedHashSet<>(logisticsRegisterSource.snapshot().channelNames());
        if (allChannels.isEmpty()) {
            // 插件快照尚未成功同步时退回历史导入表，保证老部署升级瞬间渠道清单不会突然清空。
            logisticsRepository.findCurrentVersions().stream()
                    .map(ShipmentLogisticsRecord::getChannelName)
                    .filter(TransitInventoryService::present)
                    .forEach(allChannels::add);
        }
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
     * 货件号 → 货代 / 渠道。以供应链插件快照为准，快照为空时兜底历史导入表。
     */
    private Map<String, ShipmentLogisticsInfo> logisticsByShipment() {
        LogisticsRegisterSource.LogisticsSnapshot snapshot = logisticsRegisterSource.snapshot();
        Map<String, ShipmentLogisticsInfo> result = new LinkedHashMap<>();
        if (!snapshot.isEmpty()) {
            snapshot.byShipmentNo().forEach((shipmentNo, row) -> result.put(
                    LogisticsRegisterSource.normalize(shipmentNo),
                    new ShipmentLogisticsInfo(row.channelName(), row.forwarder())));
            return result;
        }
        log.warn("供应链物流信息登记快照为空，路途库存回退到历史导入表匹配货代与渠道");
        logisticsRepository.findCurrentVersions().forEach(record -> result.putIfAbsent(
                LogisticsRegisterSource.normalize(record.getShipmentNo()),
                new ShipmentLogisticsInfo(record.getChannelName(), record.getForwarder())));
        return result;
    }

    /** 单个货件的货代与渠道（均为物流信息登记里的原值，缺失即 null）。 */
    private record ShipmentLogisticsInfo(String channelName, String forwarder) {
    }

    /**
     * 计入在途的货件编号集合。
     * 2026-10-06 起取消原「整单申收差异 ≥ 50 件」门槛：所有真实在途货件都参与货件明细、
     * 按 SKU 路途库存与补货供应的计算，不再把碎片小货件（拆单、尾货、补发）当噪音剔除。
     *
     * <p>2026-10-10 起再加一道状态白名单：只有「入库中 / 已发货 / 已申报」三种在途状态入库。
     * 「已完成」等已结案批次虽在货件表里，但不属于在途 —— 它们只进路途抽屉的「已完成」视图，
     * 不参与 SKU 路途库存、补货建议与需求预测的供应扣减。
     *
     * <p>最后过滤申收差异合计为 0 的货件（无实际在途量，进表只会是空行）。
     */
    private Set<String> keptShipmentNos() {
        Map<String, Double> unitsByShipment = new LinkedHashMap<>();
        for (ShipmentRecord record : shipmentRepository.findCurrentVersions()) {
            if (!IN_TRANSIT_STATUSES.contains(record.getLocalStatus())) {
                continue;
            }
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
        Map<String, ShipmentLogisticsInfo> logisticsByShipment = logisticsByShipment();
        Map<String, TransitChannelLeadTime> leadTimeByChannel = channelLeadTimeRepository.findAll().stream()
                .collect(Collectors.toMap(TransitChannelLeadTime::getChannelName, value -> value));
        Map<String, LocalDate> manualArrivalByShipment = new LinkedHashMap<>();
        shipmentEtaRepository.findAll().forEach(eta -> {
            if (eta.getArrivalDate() != null) {
                manualArrivalByShipment.put(eta.getShipmentNo(), eta.getArrivalDate());
            }
        });

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

            // 明细对账列一律取源表原值：不归零、不取绝对值，让货件明细与沃尔玛导出的货件表逐行对得上。
            // 注意 shipment.units 仍是归零口径，库存总览/补货/预测用的就是它，两者刻意分开。
            JsonNode snapshot = readSnapshot(record.getRawPayload());
            double declared = snapshotNumber(snapshot, COLUMN_DECLARED);
            double shipped = snapshotNumber(snapshot, COLUMN_SHIPPED);
            double signed = snapshotSignedUnits(snapshot);
            double transit = snapshotNumber(snapshot, COLUMN_TRANSIT);
            LocalDateTime updatedAt = snapshotDateTime(snapshot, COLUMN_UPDATED_AT);
            if (updatedAt != null) {
                shipment.closedAt = updatedAt;
            }
            shipment.declaredUnits += declared;
            shipment.shippedUnits += shipped;
            shipment.signedUnits += signed;
            shipment.rawTransitUnits += transit;
            if (transit < 0) {
                shipment.overUnits += -transit;
            }
            shipment.items.add(new TransitShipmentItem(
                    record.getSku(), round(declared), round(shipped), round(signed), round(transit)));

            shipment.skuCount++;
            String parentSku = present(record.getParentSku()) ? record.getParentSku() : record.getSku();
            shipment.parentSkus.add(parentSku);
        }

        List<TransitShipment> views = new ArrayList<>();
        List<TransitShipment> completed = new ArrayList<>();
        shipments.values().forEach(shipment -> {
            boolean isClosed = CLOSED_STATUS.equals(shipment.localStatus);
            if (isClosed) {
                // 已完成批次：结案后仍未到齐的货件对账视图，不参与任何库存口径。
                completed.add(new TransitShipment(
                        shipment.shipmentNo,
                        shipment.localStatus,
                        shipment.createdAt,
                        shipment.closedAt,
                        null,
                        null,
                        round(shipment.units),
                        round(shipment.declaredUnits),
                        round(shipment.shippedUnits),
                        round(shipment.signedUnits),
                        round(shipment.rawTransitUnits),
                        round(shipment.overUnits),
                        shipment.skuCount,
                        List.copyOf(shipment.parentSkus),
                        null,
                        null,
                        null,
                        null,
                        "CLOSED",
                        List.copyOf(shipment.items)
                ));
                return;
            }
            if (!kept.contains(shipment.shipmentNo)) {
                return;
            }
            ShipmentLogisticsInfo logistics = logisticsByShipment.get(
                    LogisticsRegisterSource.normalize(shipment.shipmentNo));
            String channel = logistics == null ? null : logistics.channelName();
            TransitChannelLeadTime leadTime = channel == null ? null : leadTimeByChannel.get(channel);
            Double leadDays = leadTime == null || !leadTime.isManual() || leadTime.getLeadDays() == null
                    ? null : leadTime.getLeadDays().doubleValue();
            LocalDate estimatedDeparture = estimatedDeparture(shipment.createdAt);
            LocalDate estimatedArrival = estimatedArrival(estimatedDeparture, leadDays);
            LocalDate manualArrival = manualArrivalByShipment.get(shipment.shipmentNo);
            String source = channel == null ? "UNMATCHED" : leadDays == null
                    ? "NEEDS_MANUAL" : "MANUAL";
            views.add(new TransitShipment(
                    shipment.shipmentNo,
                    shipment.localStatus,
                    shipment.createdAt,
                    null,
                    logistics == null ? null : logistics.forwarder(),
                    channel,
                    round(shipment.units),
                    round(shipment.declaredUnits),
                    round(shipment.shippedUnits),
                    round(shipment.signedUnits),
                    round(shipment.rawTransitUnits),
                    round(shipment.overUnits),
                    shipment.skuCount,
                    List.copyOf(shipment.parentSkus),
                    estimatedDeparture,
                    leadDays,
                    estimatedArrival,
                    manualArrival,
                    source,
                    List.copyOf(shipment.items)
            ));
        });
        views.sort(Comparator.comparing(TransitShipment::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        completed.sort(Comparator.comparingDouble(TransitShipment::units).reversed());
        return new TransitData(
                Map.copyOf(unitsBySku),
                views,
                completed,
                channelLeadTimeRepository.findAll().stream()
                        .map(this::toView)
                        .sorted(Comparator.comparing(TransitChannel::channelName))
                        .toList()
        );
    }

    /** 人工补充 / 清空某货件的到达日期；arrivalDate 传 null 表示清空，回到系统推算。 */
    @Transactional
    public void updateManualArrival(String shipmentNo, LocalDate arrivalDate) {
        if (!present(shipmentNo)) {
            throw new IllegalArgumentException("货件号不能为空");
        }
        if (arrivalDate != null) {
            LocalDate today = LocalDate.now();
            if (arrivalDate.isBefore(today.minusYears(2)) || arrivalDate.isAfter(today.plusYears(3))) {
                throw new IllegalArgumentException("到达日期超出合理范围（前后 2~3 年）");
            }
        }
        TransitShipmentEta value = shipmentEtaRepository.findById(shipmentNo)
                .orElseGet(() -> new TransitShipmentEta(shipmentNo, null));
        value.updateArrivalDate(arrivalDate);
        shipmentEtaRepository.save(value);
    }

    /** Returns only SKU-level in-transit supply that becomes sellable in the requested horizon. */
    @Transactional(readOnly = true)
    public List<TransitSkuSupply> suppliesAvailableWithin(LocalDate snapshotDate, int horizonDays) {
        if (snapshotDate == null || horizonDays <= 0) {
            return List.of();
        }
        Set<String> kept = keptShipmentNos();
        Map<String, ShipmentLogisticsInfo> logisticsByShipment = logisticsByShipment();
        Map<String, TransitChannelLeadTime> leadTimeByChannel = channelLeadTimeRepository.findAll().stream()
                .collect(Collectors.toMap(TransitChannelLeadTime::getChannelName, value -> value));
        List<TransitSkuSupply> result = new ArrayList<>();
        for (ShipmentRecord record : shipmentRepository.findCurrentVersions()) {
            if (!kept.contains(record.getShipmentNo())) {
                continue;
            }
            ShipmentLogisticsInfo logistics = logisticsByShipment.get(
                    LogisticsRegisterSource.normalize(record.getShipmentNo()));
            String channel = logistics == null ? null : logistics.channelName();
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

    /** 解析导入时留存的原始行快照；快照缺失或损坏时返回 null，调用方按缺列处理。 */
    private static JsonNode readSnapshot(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return null;
        }
        try {
            return SNAPSHOT_JSON.readTree(rawPayload);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 取原始行快照里的某个源列数值。
     *
     * <p>缺列、空值、非数字一律按 0 计 —— 单行脏数据不应打断整张明细表。
     * 负数原样返回（不归零），因为明细列是给人工对账用的。
     */
    private static double snapshotNumber(JsonNode snapshot, String column) {
        if (snapshot == null) {
            return 0;
        }
        JsonNode value = snapshot.get(column);
        if (value == null || value.isNull()) {
            return 0;
        }
        String text = value.asText("").trim().replace(",", "");
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return new BigDecimal(text).doubleValue();
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 解析源表「签收量(损坏)」：形如 {@code "12(1)"}，取括号前的签收数量。
     * 括号内是其中损坏件数，不参与签收量口径。无括号时按纯数字解析。
     */
    private static double snapshotSignedUnits(JsonNode snapshot) {
        String text = snapshotText(snapshot, COLUMN_SIGNED);
        if (text.isEmpty()) {
            return 0;
        }
        int bracket = text.indexOf('(');
        String number = (bracket >= 0 ? text.substring(0, bracket) : text).trim().replace(",", "");
        if (number.isEmpty()) {
            return 0;
        }
        try {
            return new BigDecimal(number).doubleValue();
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 解析源表「更新时间」为结案时间；格式 {@code yyyy-MM-dd HH:mm[:ss]}，无法解析时返回 null。 */
    private static LocalDateTime snapshotDateTime(JsonNode snapshot, String column) {
        String text = snapshotText(snapshot, column);
        if (text.isEmpty()) {
            return null;
        }
        String normalized = text.replace('/', '-').trim();
        if (normalized.length() <= 10) {
            normalized = normalized + " 00:00";
        }
        String withT = normalized.replace(' ', 'T');
        if (withT.length() == 16) {
            withT = withT + ":00";
        }
        try {
            return LocalDateTime.parse(withT);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 取快照某列的文本原值；缺列 / 空值返回空串。 */
    private static String snapshotText(JsonNode snapshot, String column) {
        if (snapshot == null) {
            return "";
        }
        JsonNode value = snapshot.get(column);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static final class MutableShipment {
        private final String shipmentNo;
        private final String localStatus;
        private final LocalDateTime createdAt;
        private LocalDateTime closedAt;
        private double units;
        private double declaredUnits;
        private double shippedUnits;
        private double signedUnits;
        private double rawTransitUnits;
        private double overUnits;
        private int skuCount;
        private final Set<String> parentSkus = new LinkedHashSet<>();
        private final List<TransitShipmentItem> items = new ArrayList<>();

        private MutableShipment(String shipmentNo, String localStatus, LocalDateTime createdAt) {
            this.shipmentNo = shipmentNo;
            this.localStatus = localStatus;
            this.createdAt = createdAt;
        }
    }

    public record TransitData(
            Map<String, Double> unitsBySku,
            List<TransitShipment> shipments,
            List<TransitShipment> completedShipments,
            List<TransitChannel> channels
    ) {
    }

    public record TransitShipment(
            String shipmentNo,
            String localStatus,
            LocalDateTime createdAt,
            // 结案时间：仅「已完成」批次有值（源表「更新时间」）。
            LocalDateTime closedAt,
            String forwarder,
            String channel,
            // 归零后的在途量（库存口径）：库存总览、补货、预测与时间段分桶都用它。
            // 「已完成」批次里它表示结案后仍未到齐的件数（= 未到量）。
            double units,
            // 申报量：源表「申报量」逐行原值合计。
            double declaredUnits,
            // 已发货：源表「已发货」逐行原值合计（状态位，通常是 0 或等于申报量）。
            double shippedUnits,
            // 签收量：源表「签收量(损坏)」中括号前的数字逐行合计。
            double signedUnits,
            // 在途量原值：源表「申收差异」逐行原值合计，可能为负（超收）。
            double rawTransitUnits,
            // 超收量：申收差异为负的行取绝对值合计。
            double overUnits,
            int skuCount,
            List<String> parentSkus,
            LocalDate estimatedDeparture,
            Double leadDays,
            LocalDate estimatedArrival,
            // 人工补充的到达日期；非空时优先于系统推算值用于分桶与倒计时。
            LocalDate manualArrival,
            String leadTimeSource,
            List<TransitShipmentItem> items
    ) {
        /** 分桶与倒计时采用的到达日：人工值优先，其次系统推算。 */
        public LocalDate effectiveArrival() {
            return manualArrival != null ? manualArrival : estimatedArrival;
        }
    }

    /** 单个 SKU 的对账明细，四个数量与货件级同口径，均取自源表原值。 */
    public record TransitShipmentItem(
            String sku,
            double declaredUnits,
            double shippedUnits,
            double signedUnits,
            double transitUnits
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

    /** 货件到达日期补录入参；arrivalDate 为空表示清空人工值。 */
    public record ArrivalPatch(LocalDate arrivalDate) {
    }
}
