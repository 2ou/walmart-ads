package com.ai.walmartads.inventory.application;

import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把领星「WFS 货件」列表接口转换为工作台既有的货件明细模型。
 *
 * <p>浏览器连接器只提交接口响应 JSON，不提交 Cookie 或令牌。新快照通过校验后才会在同一事务内
 * 切换 {@code current_version}；接口过期、分页不完整或店铺不匹配时保留最后一份可用数据。</p>
 */
@Service
public class LingxingWfsShipmentService {

    public static final Set<String> SOURCE_HOSTS = Set.of("czyx.lingxing.com", "gw.lingxingerp.com");
    public static final String SOURCE_PATH = "/mp-platform-warehouse-api/api/cargo/list";
    public static final String SOURCE_TYPE = "PROJECT_CONNECTOR";
    public static final String MCP_SOURCE_TYPE = "LINGXING_MCP";
    public static final String MCP_SOURCE_URL = "lingxing://mcp/" + LingxingWfsMcpReader.TOOL_ID;

    private static final Set<String> ACTIVE_STATUSES = Set.of("入库中", "已发货", "已申报");
    private static final Set<String> SUPPORTED_STATUSES = Set.of("入库中", "已发货", "已申报", "已完成");
    private static final Set<String> TERMINAL_STATUSES = Set.of("已完成", "已取消", "已替换", "已删除",
            "CANCELLED", "CANCELED", "REPLACED", "DELETED");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ReplenishmentPlanCache replenishmentPlanCache;
    private final ApplicationEventPublisher events;

    public LingxingWfsShipmentService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ReplenishmentPlanCache replenishmentPlanCache,
            ApplicationEventPublisher events
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.replenishmentPlanCache = replenishmentPlanCache;
        this.events = events;
    }

    @Transactional
    public ImportSummary replaceFromConnector(String sourceUrl, JsonNode response, LocalDateTime capturedAt) {
        validateSourceUrl(sourceUrl);
        if (response == null || response.isNull() || response.path("code").asInt(-1) != 1) {
            throw new IllegalArgumentException("领星 WFS 货件接口响应异常，已保留原货件数据");
        }
        JsonNode data = response.path("data");
        JsonNode listNode = data.path("list");
        if (!listNode.isArray()) {
            throw new IllegalArgumentException("领星 WFS 货件列表结构无法识别，已保留原货件数据");
        }
        ArrayNode shipments = (ArrayNode) listNode;
        int declaredTotal = data.path("total").asInt(shipments.size());
        if (declaredTotal > shipments.size()) {
            throw new IllegalArgumentException("领星 WFS 货件分页未读取完整，已保留原货件数据");
        }
        if (shipments.isEmpty()) {
            throw new IllegalArgumentException("领星 WFS 货件列表为空，已保留原货件数据");
        }
        return publish(sourceUrl, SOURCE_TYPE, shipments, normalizeCapturedAt(capturedAt));
    }

    /** MCP arrives as a fully paginated, tenant-filtered snapshot, never as a browser callback. */
    @Transactional
    public ImportSummary replaceFromMcp(ArrayNode shipments, String storeId, LocalDateTime capturedAt) {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        if (capturedAt == null || shipments == null || shipments.isEmpty() || !principal.ready()
                || !principal.storeId().equals(storeId)) {
            throw rejected("货件快照为空或店铺绑定无效");
        }
        // Lock the current binding, also serializing an old lease finishing after a newer publish.
        List<Long> locked = jdbc.queryForList("""
                select id from wm_ads_workspace where id=? and status='ACTIVE' and store_id=? for update
                """, Long.class, principal.workspaceId(), storeId);
        if (locked.isEmpty()) throw rejected("同步中店铺绑定已经变化");
        LocalDateTime lastPublished = jdbc.queryForObject("""
                select max(coalesce(synced_at, created_at)) from wm_ads_shipment_record
                 where workspace_id=? and current_version=true
                """, LocalDateTime.class, principal.workspaceId());
        if (lastPublished != null && !capturedAt.isAfter(lastPublished)) {
            throw rejected("已有更新的货件快照");
        }
        return publish(MCP_SOURCE_URL, MCP_SOURCE_TYPE, shipments, capturedAt);
    }

    private ImportSummary publish(String sourceUrl, String sourceType, ArrayNode shipments, LocalDateTime syncedAt) {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        boolean fromMcp = MCP_SOURCE_TYPE.equals(sourceType);
        String prefix = normalize(principal.storeAbbreviation());
        List<ShipmentRow> rows = new ArrayList<>();
        Set<String> importedShipments = new LinkedHashSet<>();
        Map<String, String> incomingStatuses = new HashMap<>();
        Set<ShipmentSku> incomingKeys = new HashSet<>();
        int skipped = 0;
        BigDecimal transitUnits = BigDecimal.ZERO;

        for (JsonNode shipment : shipments) {
            String shipmentNo = text(shipment, "cargoCode", "shipmentId", "shipmentNo");
            String status = normalizeStatus(text(shipment, "cargoStatusName", "cargoStatus", "localStatus"));
            JsonNode detailNode = shipment.path("cargoListGoodsDetailResponses");
            if (fromMcp) {
                if (!principal.storeId().equals(text(shipment, "storeId"))) throw rejected("货件店铺不匹配");
                incomingStatuses.put(shipmentNo, status);
                if (status != null && ACTIVE_STATUSES.contains(status) && (!detailNode.isArray() || detailNode.isEmpty())) {
                    throw rejected("在途货件缺少 SKU 明细");
                }
            }
            if (!StringUtils.hasText(shipmentNo) || status == null || !SUPPORTED_STATUSES.contains(status) || !detailNode.isArray()) {
                skipped++;
                continue;
            }
            LocalDateTime createdAt = dateTime(text(shipment, "cargoCreateDate", "createDate", "createdAt"));
            if (fromMcp && ACTIVE_STATUSES.contains(status) && createdAt == null) {
                throw rejected("在途货件缺少有效创建时间");
            }
            String updatedAt = text(shipment, "updateDate", "updatedAt");
            for (JsonNode detail : detailNode) {
                String sku = text(detail, "sku", "msku", "sellerSku");
                if (!StringUtils.hasText(sku)
                        || (StringUtils.hasText(prefix)
                        && !sku.trim().toUpperCase(Locale.ROOT).startsWith(prefix))) {
                    skipped++;
                    continue;
                }
                BigDecimal declared = decimal(detail, "declareNum");
                BigDecimal shipped = decimal(detail, "shipmentsNum");
                BigDecimal received = decimal(detail, "receivedNum");
                BigDecimal damaged = decimal(detail, "damegedQty", "damagedQty");
                BigDecimal transit = decimal(detail, "diffOfDeclareAndSign");
                if (transit == null) {
                    if (fromMcp) throw rejected("货件 SKU 缺少有效申收差异");
                    skipped++;
                    continue;
                }
                declared = zeroIfNull(declared);
                shipped = zeroIfNull(shipped);
                received = zeroIfNull(received);
                damaged = zeroIfNull(damaged);

                ObjectNode snapshot = objectMapper.createObjectNode();
                snapshot.put("申报量", declared.toPlainString());
                snapshot.put("已发货", shipped.toPlainString());
                snapshot.put("签收量(损坏)", received.toPlainString() + "(" + damaged.toPlainString() + ")");
                snapshot.put("申收差异", transit.toPlainString());
                if (StringUtils.hasText(updatedAt)) snapshot.put("更新时间", updatedAt);
                snapshot.set("领星原始明细", detail.deepCopy());

                String normalizedSku = sku.trim();
                if (fromMcp) {
                    if (!incomingKeys.add(new ShipmentSku(shipmentNo, normalize(normalizedSku)))) {
                        throw rejected("货件 SKU 明细出现重复");
                    }
                    putIfPresent(snapshot, "物流中心编号", text(shipment, "logisticsCode"));
                    putIfPresent(snapshot, "店铺", principal.storeName());
                    putIfPresent(snapshot, "货件状态", status);
                    putIfPresent(snapshot, "入库订单编号", text(shipment, "inboundOrderId"));
                }
                rows.add(new ShipmentRow(
                        shipmentNo.trim(), status, createdAt, normalizedSku, parentSku(normalizedSku),
                        transit.max(BigDecimal.ZERO), json(snapshot)
                ));
                importedShipments.add(shipmentNo.trim());
                if (!fromMcp || ACTIVE_STATUSES.contains(status)) transitUnits = transitUnits.add(transit.max(BigDecimal.ZERO));
            }
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("领星 WFS 响应未包含当前店铺前缀 "
                    + (StringUtils.hasText(prefix) ? prefix : "对应") + " 的有效货件 SKU，已保留原货件数据");
        }

        long workspaceId = principal.workspaceId();
        if (fromMcp) {
            validateActiveCoverage(workspaceId, incomingStatuses, incomingKeys);
            // Keep the legacy connector snapshot and one preceding MCP version for rollback/audit.
            jdbc.update("delete from wm_ads_shipment_record where workspace_id=? and source_type=? and current_version=false",
                    workspaceId, MCP_SOURCE_TYPE);
        } else {
            jdbc.update("delete from wm_ads_shipment_record where workspace_id=? and source_type=?",
                    workspaceId, sourceType);
        }
        jdbc.update("update wm_ads_shipment_record set current_version=false where workspace_id=? and current_version=true",
                workspaceId);
        List<Object[]> args = rows.stream().map(row -> new Object[]{
                workspaceId, sourceType, sourceUrl, syncedAt, true,
                row.shipmentNo(), row.localStatus(), row.shipmentCreatedAt(), row.sku(), row.parentSku(),
                row.receiptDifference(), row.rawPayload(), syncedAt
        }).toList();
        jdbc.batchUpdate("""
                insert into wm_ads_shipment_record(
                    workspace_id, source_type, source_url, synced_at, import_file_id, current_version,
                    shipment_no, local_status, shipment_created_at, sku, parent_sku,
                    receipt_difference, raw_payload, created_at
                ) values (?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);
        jdbc.update("""
                insert into wm_ads_lx_wfs_shipment_sync_log(
                    workspace_id, started_at, finished_at, status, source_shipment_count,
                    imported_shipment_count, imported_row_count, skipped_row_count,
                    transit_units, source_url, message
                ) values (?, ?, ?, 'SUCCESS', ?, ?, ?, ?, ?, ?, ?)
                """, workspaceId, syncedAt, LocalDateTime.now(), shipments.size(), importedShipments.size(),
                rows.size(), skipped, transitUnits, sourceUrl, fromMcp
                ? "领星 MCP 已更新 WFS 货件快照" : "领星 WFS 货件已替换当前路途货件快照");
        invalidateAfterCommit(workspaceId);
        return new ImportSummary(shipments.size(), importedShipments.size(), rows.size(), skipped,
                transitUnits, syncedAt, sourceType);
    }

    private void validateActiveCoverage(long workspaceId, Map<String, String> incomingStatuses, Set<ShipmentSku> incomingKeys) {
        List<ShipmentSku> previouslyActive = jdbc.query("""
                select shipment_no, sku from wm_ads_shipment_record
                 where workspace_id=? and current_version=true and source_type in ('PROJECT_CONNECTOR','LINGXING_MCP')
                   and local_status in ('已申报','已发货','入库中') and receipt_difference > 0
                """, (rs, ignored) -> new ShipmentSku(rs.getString("shipment_no").trim(), normalize(rs.getString("sku"))), workspaceId);
        for (ShipmentSku old : previouslyActive) {
            String newStatus = incomingStatuses.get(old.shipmentNo());
            if (newStatus == null || !(TERMINAL_STATUSES.contains(newStatus.toUpperCase(Locale.ROOT))
                    || (ACTIVE_STATUSES.contains(newStatus) && incomingKeys.contains(old)))) {
                throw rejected("此前有未签收量的货件或 SKU 在本次快照中缺失");
            }
        }
    }

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException("领星 MCP WFS " + reason + "，已保留原货件数据");
    }

    private static void putIfPresent(ObjectNode target, String key, String value) {
        if (StringUtils.hasText(value)) target.put(key, value);
    }

    /** 提交后清掉补货计划缓存，避免页面继续沿用上一版路途库存。 */
    private void invalidateAfterCommit(long workspaceId) {
        Runnable invalidate = () -> {
            replenishmentPlanCache.invalidate();
            events.publishEvent(new WfsShipmentsSyncedEvent(workspaceId));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
            return;
        }
        invalidate.run();
    }

    @Transactional(readOnly = true)
    public SourceStatus status() {
        long workspaceId = WorkspaceContext.requireId();
        List<SourceAggregate> rows = jdbc.query("""
                select source_type, max(coalesce(synced_at, created_at)) active_at,
                       count(distinct shipment_no) shipment_count, count(*) item_count,
                       coalesce(sum(case
                           when local_status in ('入库中', '已发货', '已申报') then receipt_difference
                           else 0 end),0) transit_units
                  from wm_ads_shipment_record
                 where workspace_id=? and current_version=true
                 group by source_type
                 order by active_at desc
                """, (rs, rowNum) -> new SourceAggregate(
                rs.getString("source_type"),
                rs.getTimestamp("active_at") == null ? null : rs.getTimestamp("active_at").toLocalDateTime(),
                rs.getInt("shipment_count"),
                rs.getInt("item_count"),
                rs.getBigDecimal("transit_units")
        ), workspaceId);
        if (rows.isEmpty()) {
            return new SourceStatus("NONE", null, 0, 0, BigDecimal.ZERO, false);
        }
        SourceAggregate active = rows.getFirst();
        boolean stale = MCP_SOURCE_TYPE.equals(active.sourceType())
                ? active.activeAt() == null || active.activeAt().isBefore(LocalDateTime.now().minusHours(12))
                : SOURCE_TYPE.equals(active.sourceType()) && !LingxingSourcePolicy.isAutomaticFresh(active.activeAt());
        return new SourceStatus(active.sourceType(), active.activeAt(), active.shipmentCount(), active.itemCount(),
                active.transitUnits() == null ? BigDecimal.ZERO : active.transitUnits(), stale);
    }

    private static void validateSourceUrl(String sourceUrl) {
        try {
            URI uri = URI.create(sourceUrl == null ? "" : sourceUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || SOURCE_HOSTS.stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost()))
                    || !SOURCE_PATH.equals(uri.getPath())) {
                throw new IllegalArgumentException("只接受领星 WFS 货件列表只读接口数据");
            }
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && "只接受领星 WFS 货件列表只读接口数据".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException("领星 WFS 货件接口地址无效");
        }
    }

    private static LocalDateTime normalizeCapturedAt(LocalDateTime value) {
        LocalDateTime now = LocalDateTime.now();
        if (value == null || value.isAfter(now.plusMinutes(5)) || value.isBefore(now.minusHours(2))) return now;
        return value;
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    /** 领星新版接口返回英文枚举；落库仍使用工作台既有中文状态，保持在途口径兼容。 */
    private static String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING_SHIPMENT_DETAILS" -> "已申报";
            case "AWAITING_DELIVERY" -> "已发货";
            case "RECEIVING_IN_PROGRESS" -> "入库中";
            case "CLOSED" -> "已完成";
            default -> value.trim();
        };
    }

    private static BigDecimal decimal(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) continue;
            try {
                String text = value.isNumber() ? value.decimalValue().toPlainString() : value.asText();
                if (StringUtils.hasText(text)) return new BigDecimal(text.replace(",", "").trim());
            } catch (RuntimeException ignored) {
                // Try the next known alias.
            }
        }
        return null;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static LocalDateTime dateTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().replace('/', '-');
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // Try next supported format.
            }
        }
        return null;
    }

    private static String parentSku(String sku) {
        int split = sku.indexOf('-');
        return split > 0 ? sku.substring(0, split) : sku;
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("领星 WFS 货件明细无法序列化", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ShipmentRow(String shipmentNo, String localStatus, LocalDateTime shipmentCreatedAt,
                               String sku, String parentSku, BigDecimal receiptDifference, String rawPayload) {
    }

    private record ShipmentSku(String shipmentNo, String sku) { }

    private record SourceAggregate(String sourceType, LocalDateTime activeAt, int shipmentCount,
                                   int itemCount, BigDecimal transitUnits) {
    }

    public record ImportSummary(int sourceShipments, int importedShipments, int importedRows, int skippedRows,
                                BigDecimal transitUnits, LocalDateTime syncedAt, String source) {
    }

    public record SourceStatus(String activeSource, LocalDateTime activeAt, int shipmentCount,
                               int itemCount, BigDecimal transitUnits, boolean stale) {
    }
}
