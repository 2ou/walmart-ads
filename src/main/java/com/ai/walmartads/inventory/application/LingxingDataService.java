package com.ai.walmartads.inventory.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 领星数据模块的只读聚合服务。
 *
 * <p>三块数据分别对应前端三个 Tab：</p>
 * <ul>
 *   <li>国内仓库存：项目内置连接器（{@code wm_ads_lx_domestic_inventory}）与文件上传
 *       （{@code wm_ads_domestic_inventory_snapshot}）双通道；自动快照新鲜时固定优先，
 *       仅自动快照过期且文件更新时启用文件兜底；
 *       口径与 {@link InventoryDataService#domesticBySku()} 完全一致，保证与库存总览抽屉数字对得上。</li>
 *   <li>WFS 货件：{@code wm_ads_shipment_record} 当前生效快照（{@code current_version = true}），
 *       按货件单号 + SKU 展开，申报量 / 已发货 / 签收量取自明细行原始报文。</li>
 *   <li>同步日志：仅国内仓库存与 WFS 货件两条项目内置同步链路的执行记录。</li>
 * </ul>
 *
 * <p>所有数据按款号（Parent SKU）聚合；款号优先取货件行自带值 / SKU 映射表，其次从 SKU 前缀解析。
 * 映射不上的 SKU 归入「未映射」，不隐藏，避免静默丢数。</p>
 */
@Service
public class LingxingDataService {

    static final String UNMAPPED = "（未映射）";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LingxingDataService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 国内仓

    public DomesticPayload domestic() {
        LocalDateTime automaticAt = jdbc.queryForObject(
                "select max(synced_at) from wm_ads_lx_domestic_inventory",
                new MapSqlParameterSource(), LocalDateTime.class);
        LocalDateTime uploadAt = jdbc.queryForObject("""
                select max(domestic.snapshot_at)
                  from wm_ads_domestic_inventory_snapshot domestic
                  join wm_ads_import_file file on file.id = domestic.import_file_id and file.active = true
                """, new MapSqlParameterSource(), LocalDateTime.class);
        boolean automaticActive = LingxingSourcePolicy.preferAutomatic(automaticAt, uploadAt);

        List<Map<String, Object>> raw;
        String source;
        LocalDateTime sourceAt;
        boolean warehouseAvailable;
        if (automaticActive) {
            raw = jdbc.queryForList("""
                    select sku, available_units, warehouse_name, store_name
                      from wm_ads_lx_domestic_inventory
                    """, new MapSqlParameterSource());
            source = "PROJECT_CONNECTOR";
            sourceAt = automaticAt;
            warehouseAvailable = true;
        } else {
            raw = jdbc.queryForList("""
                    select domestic.sku,
                           sum(domestic.available_units) as available_units,
                           null as warehouse_name,
                           null as store_name
                      from wm_ads_domestic_inventory_snapshot domestic
                      join wm_ads_import_file file
                        on file.id = domestic.import_file_id and file.active = true
                     where domestic.snapshot_at = (
                            select max(current_domestic.snapshot_at)
                              from wm_ads_domestic_inventory_snapshot current_domestic
                              join wm_ads_import_file current_file
                                on current_file.id = current_domestic.import_file_id and current_file.active = true
                     )
                     group by domestic.sku
                    """, new MapSqlParameterSource());
            source = uploadAt == null ? "NONE" : "FILE_UPLOAD";
            sourceAt = uploadAt;
            warehouseAvailable = false;
        }

        Map<String, StyleAccumulator> styles = aggregate(raw, warehouseAvailable);
        long totalUnits = 0;
        long skuCount = 0;
        for (StyleAccumulator style : styles.values()) {
            totalUnits += Math.round(style.units);
            skuCount += style.skus.size();
        }
        return new DomesticPayload(source, sourceLabel(source), sourceAt, automaticAt, uploadAt,
                skuCount, styles.size(), totalUnits, warehouseAvailable, sorted(styles));
    }

    // ---------------------------------------------------------------- WFS 货件

    private static final String SHIPMENT_SELECT = """
            select shipment_no, local_status, shipment_created_at, sku, parent_sku,
                   receipt_difference, raw_payload, source_type, synced_at
              from wm_ads_shipment_record
             where current_version = true
            """;

    /**
     * WFS 货件列表，以领星的货件单号为维度（一张货件一行）。
     * 数量字段不在表列里，统一从明细行 {@code raw_payload} 解析：
     * 自动同步写入时为「申报量 / 已发货 / 签收量(损坏)」，文件上传时为领星导出的同名列。
     */
    public WfsShipmentPayload wfsShipments() {
        List<Map<String, Object>> raw = loadShipmentRows(null);
        if (raw.isEmpty()) {
            return new WfsShipmentPayload(null, "NONE", "无数据", 0, 0, 0,
                    0, 0, 0, 0, null, null, List.of());
        }

        Set<String> skus = new LinkedHashSet<>();
        LocalDateTime syncedAt = null;
        String sourceType = null;
        for (Map<String, Object> row : raw) {
            String sku = text(row, "sku");
            if (sku != null && !sku.isBlank()) skus.add(sku);
            LocalDateTime rowSyncedAt = toLocalDateTime(row.get("synced_at"));
            if (rowSyncedAt != null && (syncedAt == null || rowSyncedAt.isAfter(syncedAt))) syncedAt = rowSyncedAt;
            String rowSource = text(row, "source_type");
            if (sourceType == null && rowSource != null && !rowSource.isBlank()) sourceType = rowSource;
        }

        Map<String, ShipmentBuilder> builders = groupByShipment(raw, skus);
        double declaredTotal = 0, shippedTotal = 0, receivedTotal = 0, transitTotal = 0;
        LocalDateTime earliest = null, latest = null;
        Set<String> parents = new LinkedHashSet<>();
        List<ShipmentSummary> summaries = new ArrayList<>();
        for (ShipmentBuilder builder : builders.values()) {
            parents.addAll(builder.styles.keySet());
            declaredTotal += builder.declaredUnits;
            shippedTotal += builder.shippedUnits;
            receivedTotal += builder.receivedUnits;
            transitTotal += builder.transitUnits;
            if (builder.createdAt != null) {
                if (earliest == null || builder.createdAt.isBefore(earliest)) earliest = builder.createdAt;
                if (latest == null || builder.createdAt.isAfter(latest)) latest = builder.createdAt;
            }
            summaries.add(builder.toSummary());
        }
        summaries.sort(Comparator
                .comparing((ShipmentSummary node) -> node.createdAt() == null
                        ? LocalDateTime.MIN : node.createdAt()).reversed()
                .thenComparing(ShipmentSummary::shipmentNo));

        String source = sourceType == null || sourceType.isBlank() ? "NONE" : sourceType;
        return new WfsShipmentPayload(syncedAt, source, shipmentSourceLabel(source),
                summaries.size(), skus.size(), parents.size(),
                Math.round(declaredTotal), Math.round(shippedTotal),
                Math.round(receivedTotal), Math.round(transitTotal),
                earliest, latest, summaries);
    }

    /** 单张货件的仪表盘数据：元信息 + 按款申报分布 + SKU 明细。 */
    public WfsShipmentDetail wfsShipmentDetail(String shipmentNo) {
        if (shipmentNo == null || shipmentNo.isBlank()) return null;
        List<Map<String, Object>> raw = loadShipmentRows(shipmentNo.trim());
        if (raw.isEmpty()) return null;

        Set<String> skus = new LinkedHashSet<>();
        for (Map<String, Object> row : raw) {
            String sku = text(row, "sku");
            if (sku != null && !sku.isBlank()) skus.add(sku);
        }
        ShipmentBuilder builder = groupByShipment(raw, skus).values().iterator().next();
        return builder.toDetail();
    }

    private List<Map<String, Object>> loadShipmentRows(String shipmentNo) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String filter = "";
        if (shipmentNo != null) {
            filter = " and shipment_no = :shipmentNo";
            params.addValue("shipmentNo", shipmentNo);
        }
        return jdbc.queryForList(SHIPMENT_SELECT + filter
                + " order by shipment_created_at desc, shipment_no, sku", params);
    }

    private Map<String, ShipmentBuilder> groupByShipment(List<Map<String, Object>> raw, Set<String> skus) {
        Map<String, ProductInfo> products = loadProducts(skus);
        Map<String, ShipmentBuilder> builders = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            String sku = text(row, "sku");
            if (sku == null || sku.isBlank()) continue;
            String shipmentNo = text(row, "shipment_no");
            ProductInfo info = products.get(sku);
            String parent = firstNonBlank(
                    text(row, "parent_sku"),
                    info == null ? null : info.parentSku(),
                    parseParent(sku),
                    UNMAPPED);
            JsonNode payload = readJson(text(row, "raw_payload"));
            double declared = payloadNumber(payload, "申报量");
            double shipped = payloadNumber(payload, "已发货");
            double received = payloadNumber(payload, "签收量(损坏)");
            double transit = Math.max(0, number(row, "receipt_difference"));

            ShipmentBuilder shipment = builders.computeIfAbsent(
                    shipmentNo == null ? "" : shipmentNo, ShipmentBuilder::new);
            shipment.fill(
                    text(row, "local_status"),
                    toLocalDateTime(row.get("shipment_created_at")),
                    payload == null ? null : payloadText(payload, "物流中心编号"),
                    payload == null ? null : payloadText(payload, "店铺"),
                    payload == null ? null : payloadText(payload, "货件状态"),
                    payload == null ? null : payloadText(payload, "收件城市"),
                    payload == null ? null : payloadText(payload, "收件州/省"),
                    payload == null ? null : payloadText(payload, "入库订单编号"));

            shipment.addLine(parent, sku, info, declared, shipped, received, transit);
        }
        return builders;
    }

    // ---------------------------------------------------------------- 同步日志

    /**
     * 仅两条插件同步链路：国内仓库存（{@code wm_ads_lx_domestic_inventory_sync_log}）与
     * WFS 货件（{@code wm_ads_lx_wfs_shipment_sync_log}）。其余订单 / 评论 / 广告 / Listing / 供应链
     * 属于定时任务，不在此模块呈现。
     */
    public List<SyncLogEntry> syncLogs() {
        List<SyncLogEntry> entries = new ArrayList<>();
        entries.addAll(queryLogs("""
                select started_at, finished_at, status,
                       imported_sku_count as record_count,
                       concat('来源行 ', coalesce(source_row_count, 0),
                              ' · 跳过 ', coalesce(skipped_row_count, 0),
                              ' · 可用 ', coalesce(available_units, 0)) as detail,
                       coalesce(message, '') as message,
                       '项目内置连接器' as source,
                       null as triggered_by
                  from wm_ads_lx_domestic_inventory_sync_log
                 order by id desc limit 30
                """, "DOMESTIC", "国内仓库存"));
        entries.addAll(queryLogs("""
                select started_at, finished_at, status,
                       imported_shipment_count as record_count,
                       concat('货件 ', coalesce(source_shipment_count, 0),
                              ' · 明细行 ', coalesce(imported_row_count, 0),
                              ' · 申收差异 ', coalesce(transit_units, 0)) as detail,
                       coalesce(message, '') as message,
                       '项目内置连接器' as source,
                       null as triggered_by
                  from wm_ads_lx_wfs_shipment_sync_log
                 order by id desc limit 30
                """, "WFS_SHIPMENT", "WFS 货件"));

        entries.sort((left, right) -> {
            if (left.startedAt() == null && right.startedAt() == null) return 0;
            if (left.startedAt() == null) return 1;
            if (right.startedAt() == null) return -1;
            return right.startedAt().compareTo(left.startedAt());
        });
        return entries.size() > 200 ? entries.subList(0, 200) : entries;
    }

    private List<SyncLogEntry> queryLogs(String sql, String dataset, String label) {
        List<SyncLogEntry> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, new MapSqlParameterSource())) {
            Object started = row.get("started_at");
            Object finished = row.get("finished_at");
            result.add(new SyncLogEntry(
                    dataset,
                    label,
                    started instanceof LocalDateTime value ? value : null,
                    finished instanceof LocalDateTime value ? value : null,
                    text(row, "status"),
                    (long) number(row, "record_count"),
                    text(row, "detail"),
                    text(row, "message"),
                    text(row, "source"),
                    text(row, "triggered_by")
            ));
        }
        return result;
    }

    // ---------------------------------------------------------------- 聚合

    /**
     * 把 SKU 行按款号聚合。款号优先取产品记录/映射表，取不到再从 SKU 前缀解析。
     * WFS 快照表自带 parent_sku，缺失行同样回落到前缀解析。
     */
    private Map<String, StyleAccumulator> aggregate(List<Map<String, Object>> rows, boolean warehouseAvailable) {
        Map<String, StyleAccumulator> styles = new LinkedHashMap<>();
        if (rows.isEmpty()) return styles;

        Set<String> skus = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String sku = text(row, "sku");
            if (sku != null && !sku.isBlank()) skus.add(sku);
        }
        Map<String, ProductInfo> products = loadProducts(skus);

        for (Map<String, Object> row : rows) {
            String sku = text(row, "sku");
            if (sku == null || sku.isBlank()) continue;
            double units = number(row, "available_units");
            // 国内仓页面只展示当前真正有货的 SKU；零库存与负库存不参与款号、SKU 数及库存合计。
            if (units <= 0) continue;
            ProductInfo info = products.get(sku);
            String parent = firstNonBlank(
                    info == null ? null : info.parentSku(),
                    text(row, "parent_sku"),
                    parseParent(sku),
                    UNMAPPED);
            String itemId = firstNonBlank(text(row, "item_id"),
                    info == null ? null : info.itemId());
            String itemName = firstNonBlank(text(row, "item_name"),
                    info == null ? null : info.itemName());

            StyleAccumulator style = styles.computeIfAbsent(parent,
                    key -> new StyleAccumulator(key));
            style.units += units;
            style.link(itemName, info == null ? null : info.imageUrl(), itemId);
            style.skus.add(new SkuLine(
                    sku,
                    units,
                    info == null ? null : info.color(),
                    info == null ? null : info.size(),
                    warehouseAvailable ? text(row, "warehouse_name") : null,
                    warehouseAvailable ? text(row, "store_name") : null,
                    info == null ? null : info.itemId()
            ));
        }
        return styles;
    }

    private Map<String, ProductInfo> loadProducts(Set<String> skus) {
        Map<String, ProductInfo> result = new LinkedHashMap<>();
        if (skus.isEmpty()) return result;
        MapSqlParameterSource params = new MapSqlParameterSource("skus", skus);
        jdbc.queryForList("""
                select sku,
                       max(parent_sku) as parent_sku,
                       max(color) as color,
                       max(size) as size,
                       max(main_image_url) as image_url,
                       max(product_name) as product_name
                  from wm_ads_product_record
                 where current_version = true and sku in (:skus)
                 group by sku
                """, params).forEach(row ->
                result.put(text(row, "sku"), new ProductInfo(
                        text(row, "parent_sku"), text(row, "color"), text(row, "size"),
                        text(row, "image_url"), text(row, "product_name"), null)));
        // 产品记录没覆盖的 SKU 再补映射表（映射表是款号权威来源，但不带颜色尺码）
        List<String> missingSkus = skus.stream().filter(sku -> !result.containsKey(sku)).toList();
        if (!missingSkus.isEmpty()) {
            jdbc.queryForList("""
                    select sku, max(parent_sku) as parent_sku
                      from wm_ads_sku_mapping
                     where current_version = true and sku in (:skus)
                     group by sku
                    """, new MapSqlParameterSource("skus", missingSkus)).forEach(row ->
                    result.put(text(row, "sku"), new ProductInfo(
                            text(row, "parent_sku"), null, null, null, null, null)));
        }
        return result;
    }

    private static String parseParent(String sku) {
        int dash = sku.indexOf('-');
        String head = dash > 0 ? sku.substring(0, dash) : sku;
        return head.toUpperCase(Locale.ROOT).startsWith("PNK") ? head : null;
    }

    private static List<StyleNode> sorted(Map<String, StyleAccumulator> styles) {
        List<StyleNode> nodes = new ArrayList<>();
        for (StyleAccumulator style : styles.values()) {
            List<SkuLine> lines = new ArrayList<>(style.skus);
            lines.sort(Comparator.comparingDouble(SkuLine::units).reversed()
                    .thenComparing(SkuLine::sku));
            nodes.add(new StyleNode(style.parentSku, style.units, lines.size(),
                    style.itemName, style.imageUrl, style.itemId, lines));
        }
        // 未映射固定排最后，其余按可用件数降序
        nodes.sort(Comparator
                .comparing((StyleNode node) -> UNMAPPED.equals(node.parentSku()))
                .thenComparing(Comparator.comparingDouble(StyleNode::units).reversed()));
        return nodes;
    }

    private static String sourceLabel(String source) {
        return switch (source) {
            case "PROJECT_CONNECTOR" -> "工作台自动同步";
            case "FILE_UPLOAD" -> "文件上传";
            default -> "无数据";
        };
    }

    private static String shipmentSourceLabel(String source) {
        return switch (source) {
            case "PROJECT_CONNECTOR" -> "工作台自动同步";
            case "FILE_UPLOAD" -> "文件上传";
            default -> "无数据";
        };
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 从明细行原始报文里取数量，「签收量(损坏)」这类 {@code 3(0)} 形式只取括号前的签收数。 */
    private static double payloadNumber(JsonNode payload, String key) {
        if (payload == null) return 0;
        JsonNode value = payload.get(key);
        if (value == null || value.isNull()) return 0;
        String text = value.asText("").trim();
        int paren = text.indexOf('(');
        if (paren > 0) text = text.substring(0, paren);
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 从明细行原始报文里取字符串，货件级字段（物流中心 / 店铺 / 收件地）常只出现在首行。 */
    private static String payloadText(JsonNode payload, String key) {
        if (payload == null) return null;
        JsonNode value = payload.get(key);
        if (value == null || value.isNull()) return null;
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay();
        return null;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static double number(Map<String, Object> row, Object key) {
        Object value = row.get(key);
        if (value == null) return 0;
        if (value instanceof BigDecimal decimal) return decimal.doubleValue();
        if (value instanceof Number num) return num.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static final class StyleAccumulator {
        private final String parentSku;
        private double units;
        private String itemName;
        private String imageUrl;
        private String itemId;
        private final List<SkuLine> skus = new ArrayList<>();

        private StyleAccumulator(String parentSku) {
            this.parentSku = parentSku;
        }

        private void link(String name, String image, String item) {
            if (itemName == null && name != null && !name.isBlank()) itemName = name;
            if (imageUrl == null && image != null && !image.isBlank()) imageUrl = image;
            if (itemId == null && item != null && !item.isBlank()) itemId = item;
        }
    }

    /** 单张货件的累加器：货件级元信息 + 款分布 + SKU 明细。 */
    private static final class ShipmentBuilder {
        private final String shipmentNo;
        private String localStatus;
        private String cargoStatus;
        private String warehouse;
        private String store;
        private String city;
        private String state;
        private String wmsOrderNo;
        private LocalDateTime createdAt;
        private double declaredUnits;
        private double shippedUnits;
        private double receivedUnits;
        private double transitUnits;
        private int lineCount;
        private final Set<String> skus = new LinkedHashSet<>();
        private final Map<String, StyleBuilder> styles = new LinkedHashMap<>();

        private ShipmentBuilder(String shipmentNo) {
            this.shipmentNo = shipmentNo;
        }

        private static String firstValue(String current, String next) {
            return current != null && !current.isBlank() ? current : (next == null || next.isBlank() ? current : next);
        }

        private void fill(String status, LocalDateTime created, String warehouse, String store, String cargo,
                          String city, String state, String wmsOrderNo) {
            localStatus = firstValue(localStatus, status);
            if (createdAt == null) createdAt = created;
            this.warehouse = firstValue(this.warehouse, warehouse);
            this.store = firstValue(this.store, store);
            cargoStatus = firstValue(cargoStatus, cargo);
            this.city = firstValue(this.city, city);
            this.state = firstValue(this.state, state);
            this.wmsOrderNo = firstValue(this.wmsOrderNo, wmsOrderNo);
        }

        private void addLine(String parent, String sku, ProductInfo info,
                             double declared, double shipped, double received, double transit) {
            declaredUnits += declared;
            shippedUnits += shipped;
            receivedUnits += received;
            transitUnits += transit;
            lineCount++;
            skus.add(sku);
            styles.computeIfAbsent(parent, StyleBuilder::new)
                    .addLine(sku, info, declared, shipped, received, transit);
        }

        private ShipmentSummary toSummary() {
            return new ShipmentSummary(shipmentNo, localStatus, cargoStatus, createdAt,
                    warehouse, store, city, state, wmsOrderNo,
                    styles.size(), skus.size(), lineCount,
                    declaredUnits, shippedUnits, receivedUnits, transitUnits);
        }

        private WfsShipmentDetail toDetail() {
            List<ShipmentStyleShare> shares = new ArrayList<>();
            for (StyleBuilder style : styles.values()) shares.add(style.toShare());
            shares.sort(Comparator
                    .comparing((ShipmentStyleShare share) -> UNMAPPED.equals(share.parentSku()))
                    .thenComparing(Comparator.comparingDouble(ShipmentStyleShare::declaredUnits).reversed())
                    .thenComparing(ShipmentStyleShare::parentSku));

            List<ShipmentLine> lines = new ArrayList<>();
            for (StyleBuilder style : styles.values()) lines.addAll(style.lines);
            lines.sort(Comparator.comparingDouble(ShipmentLine::declaredUnits).reversed()
                    .thenComparing(ShipmentLine::sku));

            double rate = declaredUnits > 0 ? receivedUnits / declaredUnits : 0;
            return new WfsShipmentDetail(shipmentNo, localStatus, cargoStatus, createdAt,
                    warehouse, store, city, state, wmsOrderNo,
                    styles.size(), skus.size(), lineCount,
                    declaredUnits, shippedUnits, receivedUnits, transitUnits, rate, shares, lines);
        }
    }

    private static final class StyleBuilder {
        private final String parentSku;
        private String itemName;
        private double declaredUnits;
        private double shippedUnits;
        private double receivedUnits;
        private double transitUnits;
        private int lineCount;
        private final Set<String> skus = new LinkedHashSet<>();
        private final List<ShipmentLine> lines = new ArrayList<>();

        private StyleBuilder(String parentSku) {
            this.parentSku = parentSku;
        }

        private void addLine(String sku, ProductInfo info,
                             double declared, double shipped, double received, double transit) {
            declaredUnits += declared;
            shippedUnits += shipped;
            receivedUnits += received;
            transitUnits += transit;
            lineCount++;
            skus.add(sku);
            if (itemName == null && info != null && info.itemName() != null && !info.itemName().isBlank()) {
                itemName = info.itemName();
            }
            lines.add(new ShipmentLine(sku, parentSku,
                    info == null ? null : info.color(),
                    info == null ? null : info.size(),
                    declared, shipped, received, transit));
        }

        private ShipmentStyleShare toShare() {
            return new ShipmentStyleShare(parentSku, itemName, skus.size(), lineCount,
                    declaredUnits, shippedUnits, receivedUnits, transitUnits);
        }
    }

    private record ProductInfo(String parentSku, String color, String size,
                               String imageUrl, String itemName, String itemId) {
    }

    // ---------------------------------------------------------------- 返回结构

    public record SkuLine(String sku, double units, String color, String size,
                          String warehouse, String store, String itemId) {
    }

    public record StyleNode(String parentSku, double units, int skuCount,
                            String itemName, String imageUrl, String itemId, List<SkuLine> skus) {
    }

    public record DomesticPayload(String source, String sourceLabel, LocalDateTime sourceAt,
                                  LocalDateTime automaticAt, LocalDateTime uploadAt,
                                  long skuCount, long styleCount, long totalUnits,
                                  boolean warehouseAvailable, List<StyleNode> styles) {
    }

    public record ShipmentLine(String sku, String parentSku, String color, String size,
                               double declaredUnits, double shippedUnits,
                               double receivedUnits, double transitUnits) {
    }

    public record ShipmentStyleShare(String parentSku, String itemName, int skuCount, int lineCount,
                                     double declaredUnits, double shippedUnits,
                                     double receivedUnits, double transitUnits) {
    }

    public record ShipmentSummary(String shipmentNo, String localStatus, String cargoStatus,
                                  LocalDateTime createdAt, String warehouse, String store,
                                  String city, String state, String wmsOrderNo,
                                  int styleCount, int skuCount, int lineCount,
                                  double declaredUnits, double shippedUnits,
                                  double receivedUnits, double transitUnits) {
    }

    public record WfsShipmentDetail(String shipmentNo, String localStatus, String cargoStatus,
                                    LocalDateTime createdAt, String warehouse, String store,
                                    String city, String state, String wmsOrderNo,
                                    int styleCount, int skuCount, int lineCount,
                                    double declaredUnits, double shippedUnits,
                                    double receivedUnits, double transitUnits, double receiveRate,
                                    List<ShipmentStyleShare> styles, List<ShipmentLine> lines) {
    }

    public record WfsShipmentPayload(LocalDateTime syncedAt, String source, String sourceLabel,
                                     long shipmentCount, long skuCount, long styleCount,
                                     long declaredUnits, long shippedUnits, long receivedUnits,
                                     long transitUnits, LocalDateTime earliestShipmentAt,
                                     LocalDateTime latestShipmentAt, List<ShipmentSummary> shipments) {
    }

    public record SyncLogEntry(String dataset, String datasetLabel, LocalDateTime startedAt,
                               LocalDateTime finishedAt, String status, long recordCount,
                               String detail, String message, String source, String triggeredBy) {
    }
}
