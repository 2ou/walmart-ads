package com.ai.walmartads.inventory.application;

import com.ai.walmartads.config.DataConnectorProperties;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 接收项目内置连接器读取到的领星库存明细，并以 SKU 为粒度维护当前国内仓库存。
 *
 * <p>浏览器只提交接口响应，不提交 Cookie 或令牌。只有完整且至少包含一个当前店铺 SKU 的响应
 * 才会替换当前快照，避免登录过期、筛选错店铺或接口字段变化时把库存静默清空。</p>
 */
@Service
public class LingxingDomesticInventoryService {

    private static final Logger log = LoggerFactory.getLogger(LingxingDomesticInventoryService.class);
    private static final DateTimeFormatter DUMP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static final String SOURCE_HOST = "czyx.lingxing.com";
    public static final String SOURCE_PATH = "/api/storage/lists";

    private static final List<String> SKU_KEYS = List.of(
            "sku", "seller_sku", "sellerSku", "product_sku", "productSku",
            "stock_sku", "stockSku", "local_sku", "localSku", "msku", "fnsku"
    );
    private static final List<String> AVAILABLE_KEYS = List.of(
            "available", "available_num", "availableNum", "available_quantity", "availableQuantity",
            "available_qty", "availableQty", "available_stock", "availableStock", "stock_available",
            "quantity_available", "sellable_quantity", "sellableQuantity", "usable", "usable_quantity",
            "usableQuantity", "can_use_num", "canUseNum"
    );
    private static final List<String> BIN_AVAILABLE_KEYS = List.of(
            "product_total", "productTotal", "available", "available_num", "availableNum",
            "available_quantity", "availableQuantity", "usable_quantity", "usableQuantity"
    );
    private static final List<String> WAREHOUSE_KEYS = List.of(
            "warehouse_name", "warehouseName", "ware_house_name", "wareHouseName", "wh_name", "whName"
    );
    private static final List<String> STORE_KEYS = List.of(
            "store_name", "storeName", "seller_name", "sellerName", "account_name", "accountName",
            "shop_name", "shopName"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DataConnectorProperties connectorProperties;

    public LingxingDomesticInventoryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LingxingDomesticInventoryService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                            DataConnectorProperties connectorProperties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.connectorProperties = connectorProperties;
    }

    @Transactional
    public ImportSummary replaceFromConnector(String sourceUrl, JsonNode response, LocalDateTime capturedAt) {
        validateSourceUrl(sourceUrl);
        if (response == null || response.isNull() || response.isMissingNode()) {
            throw new IllegalArgumentException("领星库存接口响应为空，已保留原库存");
        }
        ArrayNode rows = findInventoryRows(response);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("领星库存接口没有返回可识别的 SKU 行，已保留原库存");
        }

        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        String prefix = normalize(principal.storeAbbreviation());
        LocalDateTime syncedAt = normalizeCapturedAt(capturedAt);
        Map<String, Accumulator> bySku = new LinkedHashMap<>();
        int skipped = 0;
        int noSku = 0;
        int noAvailable = 0;
        int negative = 0;
        int prefixMismatch = 0;
        List<String> foreignSamples = new ArrayList<>();
        for (JsonNode row : rows) {
            String sku = firstText(row, SKU_KEYS);
            BigDecimal available = availableFor(row);
            if (!StringUtils.hasText(sku)) {
                skipped++;
                noSku++;
                continue;
            }
            if (available == null) {
                skipped++;
                noAvailable++;
                continue;
            }
            if (available.signum() < 0) {
                skipped++;
                negative++;
                continue;
            }
            String normalizedSku = sku.trim();
            if (StringUtils.hasText(prefix)
                    && !normalizedSku.toUpperCase(Locale.ROOT).startsWith(prefix)) {
                skipped++;
                prefixMismatch++;
                if (foreignSamples.size() < 5) foreignSamples.add(normalizedSku);
                continue;
            }
            Accumulator value = bySku.computeIfAbsent(normalizedSku,
                    ignored -> new Accumulator(normalizedSku, firstText(row, WAREHOUSE_KEYS),
                            firstText(row, STORE_KEYS), row));
            value.available = value.available.add(available);
            value.addWarehouse(firstText(row, WAREHOUSE_KEYS));
            value.addStore(firstText(row, STORE_KEYS));
        }
        if (bySku.isEmpty()) {
            String detail = String.format(
                    "共读入 %d 行：无 SKU %d、无可识别可用库存 %d、负库存 %d、前缀不符 %d%s",
                    rows.size(), noSku, noAvailable, negative, prefixMismatch,
                    foreignSamples.isEmpty() ? "" : "；不符示例 " + String.join(" / ", foreignSamples));
            dumpFailure(sourceUrl, response, detail, prefix);
            throw new IllegalArgumentException("领星库存响应未包含当前店铺前缀 "
                    + (StringUtils.hasText(prefix) ? prefix : "对应") + " 的 SKU（" + detail + "），已保留原库存");
        }

        long workspaceId = principal.workspaceId();
        jdbc.update("delete from wm_ads_lx_domestic_inventory where workspace_id=?", workspaceId);
        List<Object[]> args = new ArrayList<>(bySku.size());
        for (Accumulator value : bySku.values()) {
            args.add(new Object[]{
                    workspaceId,
                    value.sku,
                    value.available,
                    join(value.warehouses),
                    join(value.stores),
                    sourceUrl,
                    json(value.sample),
                    syncedAt,
                    syncedAt,
                    syncedAt
            });
        }
        jdbc.batchUpdate("""
                insert into wm_ads_lx_domestic_inventory(
                    workspace_id, sku, available_units, warehouse_name, store_name,
                    source_url, raw_payload, synced_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);

        BigDecimal units = bySku.values().stream()
                .map(value -> value.available)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("""
                insert into wm_ads_lx_domestic_inventory_sync_log(
                    workspace_id, started_at, finished_at, status, source_row_count,
                    imported_sku_count, skipped_row_count, available_units, source_url, message
                ) values (?, ?, ?, 'SUCCESS', ?, ?, ?, ?, ?, ?)
                """, workspaceId, syncedAt, LocalDateTime.now(), rows.size(), bySku.size(), skipped,
                units, sourceUrl, "领星库存明细已替换当前国内仓库存");
        return new ImportSummary(rows.size(), bySku.size(), skipped, units, syncedAt, "PROJECT_CONNECTOR");
    }

    @Transactional(readOnly = true)
    public SourceStatus status() {
        LocalDateTime automaticAt = jdbc.queryForObject(
                "select max(synced_at) from wm_ads_lx_domestic_inventory", LocalDateTime.class);
        LocalDateTime uploadAt = jdbc.queryForObject("""
                select max(domestic.snapshot_at)
                  from wm_ads_domestic_inventory_snapshot domestic
                  join wm_ads_import_file file on file.id=domestic.import_file_id and file.active=true
                """, LocalDateTime.class);
        boolean automaticActive = LingxingSourcePolicy.preferAutomatic(automaticAt, uploadAt);
        String activeSource = automaticActive ? "PROJECT_CONNECTOR" : uploadAt == null ? "NONE" : "FILE_UPLOAD";
        Integer skuCount = automaticActive
                ? jdbc.queryForObject("select count(*) from wm_ads_lx_domestic_inventory", Integer.class)
                : jdbc.queryForObject("""
                        select count(distinct domestic.sku)
                          from wm_ads_domestic_inventory_snapshot domestic
                          join wm_ads_import_file file on file.id=domestic.import_file_id and file.active=true
                         where domestic.snapshot_at=(
                               select max(current_domestic.snapshot_at)
                                 from wm_ads_domestic_inventory_snapshot current_domestic
                                 join wm_ads_import_file current_file
                                   on current_file.id=current_domestic.import_file_id and current_file.active=true
                         )
                        """, Integer.class);
        BigDecimal units = automaticActive
                ? jdbc.queryForObject("select coalesce(sum(available_units),0) from wm_ads_lx_domestic_inventory",
                BigDecimal.class)
                : jdbc.queryForObject("""
                        select coalesce(sum(domestic.available_units),0)
                          from wm_ads_domestic_inventory_snapshot domestic
                          join wm_ads_import_file file on file.id=domestic.import_file_id and file.active=true
                         where domestic.snapshot_at=(
                               select max(current_domestic.snapshot_at)
                                 from wm_ads_domestic_inventory_snapshot current_domestic
                                 join wm_ads_import_file current_file
                                   on current_file.id=current_domestic.import_file_id and current_file.active=true
                         )
                        """, BigDecimal.class);
        LocalDateTime activeAt = automaticActive ? automaticAt : uploadAt;
        boolean stale = automaticActive && !LingxingSourcePolicy.isAutomaticFresh(automaticAt);
        return new SourceStatus(activeSource, activeAt, automaticAt, uploadAt,
                skuCount == null ? 0 : skuCount, units == null ? BigDecimal.ZERO : units, stale);
    }

    private static LocalDateTime normalizeCapturedAt(LocalDateTime value) {
        LocalDateTime now = LocalDateTime.now();
        if (value == null || value.isAfter(now.plusMinutes(5)) || value.isBefore(now.minusHours(2))) {
            return now;
        }
        return value;
    }

    private static void validateSourceUrl(String sourceUrl) {
        try {
            URI uri = URI.create(sourceUrl == null ? "" : sourceUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !SOURCE_HOST.equalsIgnoreCase(uri.getHost())
                    || !SOURCE_PATH.equals(uri.getPath())) {
                throw new IllegalArgumentException("只接受领星库存明细只读接口数据");
            }
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && "只接受领星库存明细只读接口数据".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException("领星库存接口地址无效");
        }
    }

    private static ArrayNode findInventoryRows(JsonNode root) {
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(root, 0));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            JsonNode node = current.node();
            if (node.isArray() && looksLikeInventoryRows(node)) {
                return (ArrayNode) node;
            }
            if (current.depth() >= 5) continue;
            if (node.isObject()) {
                List<String> preferred = List.of("data", "list", "rows", "records", "result", "items");
                for (String key : preferred) {
                    JsonNode child = node.get(key);
                    if (child != null && (child.isObject() || child.isArray())) {
                        queue.addLast(new NodeDepth(child, current.depth() + 1));
                    }
                }
                node.fields().forEachRemaining(entry -> {
                    if (!preferred.contains(entry.getKey())
                            && (entry.getValue().isObject() || entry.getValue().isArray())) {
                        queue.addLast(new NodeDepth(entry.getValue(), current.depth() + 1));
                    }
                });
            }
        }
        return null;
    }

    private static boolean looksLikeInventoryRows(JsonNode node) {
        int inspected = 0;
        for (JsonNode row : node) {
            if (!row.isObject()) continue;
            inspected++;
            if (hasAny(row, SKU_KEYS) && availableFor(row) != null) return true;
            if (inspected >= 10) break;
        }
        return false;
    }

    private static boolean hasAny(JsonNode row, List<String> keys) {
        return keys.stream().anyMatch(key -> row.hasNonNull(key));
    }

    private static String firstText(JsonNode row, List<String> keys) {
        for (String key : keys) {
            JsonNode value = row.get(key);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static BigDecimal firstDecimal(JsonNode row, List<String> keys) {
        for (String key : keys) {
            JsonNode value = row.get(key);
            if (value == null || value.isNull()) continue;
            try {
                String text = value.isNumber() ? value.decimalValue().toPlainString() : value.asText();
                if (StringUtils.hasText(text)) return new BigDecimal(text.replace(",", "").trim());
            } catch (RuntimeException ignored) {
                // Try the next documented alias.
            }
        }
        return null;
    }

    private static BigDecimal availableFor(JsonNode row) {
        BigDecimal direct = firstDecimal(row, AVAILABLE_KEYS);
        if (direct != null) return direct;
        JsonNode bins = row.get("bin_arr");
        if (bins == null || !bins.isArray()) return null;
        BigDecimal total = BigDecimal.ZERO;
        boolean found = false;
        for (JsonNode bin : bins) {
            BigDecimal quantity = firstDecimal(bin, BIN_AVAILABLE_KEYS);
            if (quantity == null) continue;
            total = total.add(quantity);
            found = true;
        }
        return found ? total : null;
    }

    /**
     * 前缀不符时把原始响应落盘，便于判断是「领星页面带了筛选条件」还是「接口字段改名」。
     * 落盘失败不影响主流程报错。
     */
    private void dumpFailure(String sourceUrl, JsonNode response, String detail, String prefix) {
        if (connectorProperties == null) return;
        try {
            Path dir = connectorProperties.resolvedDataDir().resolve("diagnostics");
            Files.createDirectories(dir);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("capturedAt", LocalDateTime.now().toString());
            payload.put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
            payload.put("storePrefix", prefix == null ? "" : prefix);
            payload.put("detail", detail);
            payload.set("response", response);
            Path file = dir.resolve("lx-inventory-fail-" + DUMP_STAMP.format(LocalDateTime.now()) + ".json");
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(payload), StandardCharsets.UTF_8);
            log.warn("领星国内仓库存同步失败，原始响应已保存 {}：{}", file, detail);
        } catch (Exception exception) {
            log.warn("领星国内仓库存失败响应落盘失败：{}", exception.getMessage());
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("领星库存行无法序列化", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String join(Set<String> values) {
        return values.isEmpty() ? null : String.join("、", values);
    }

    private static final class Accumulator {
        private final String sku;
        private BigDecimal available = BigDecimal.ZERO;
        private final Set<String> warehouses = new LinkedHashSet<>();
        private final Set<String> stores = new LinkedHashSet<>();
        private final JsonNode sample;

        private Accumulator(String sku, String warehouse, String store, JsonNode sample) {
            this.sku = sku;
            this.sample = sample;
            addWarehouse(warehouse);
            addStore(store);
        }

        private void addWarehouse(String value) {
            if (StringUtils.hasText(value)) warehouses.add(value.trim());
        }

        private void addStore(String value) {
            if (StringUtils.hasText(value)) stores.add(value.trim());
        }
    }

    private record NodeDepth(JsonNode node, int depth) {
    }

    public record ImportSummary(int sourceRows, int importedSkus, int skippedRows,
                                BigDecimal availableUnits, LocalDateTime syncedAt, String source) {
    }

    public record SourceStatus(String activeSource, LocalDateTime activeAt, LocalDateTime automaticAt,
                               LocalDateTime uploadAt, int skuCount, BigDecimal availableUnits, boolean stale) {
    }
}
