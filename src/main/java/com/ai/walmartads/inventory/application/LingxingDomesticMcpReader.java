package com.ai.walmartads.inventory.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Read a complete store-owned domestic inventory snapshot without trusting browser filters. */
final class LingxingDomesticMcpReader {
    private static final int PAGE_SIZE = 200;
    private static final int MAX_ROWS = 100_000;
    private final ObjectMapper mapper;
    private final BiFunction<String, Map<String, Object>, JsonNode> call;

    LingxingDomesticMcpReader(ObjectMapper mapper, BiFunction<String, Map<String, Object>, JsonNode> call) {
        this.mapper = mapper;
        this.call = call;
    }

    ObjectNode read(String storeId) {
        if (storeId == null || storeId.isBlank()) throw new IllegalArgumentException("当前店铺未绑定领星店铺 ID");
        Set<String> warehouseIds = new LinkedHashSet<>();
        for (JsonNode row : pages("warehouse_realtime_inventory_list", Map.of("type", List.of("1")))) {
            String id = row.path("wh_id").asText("").trim();
            if (!id.matches("[1-9][0-9]*")) throw new IllegalStateException("本地仓列表含无效仓库 ID，已保留原库存");
            if (!warehouseIds.add(id)) throw new IllegalStateException("本地仓列表出现重复仓库，已保留原库存");
        }
        if (warehouseIds.isEmpty()) throw new IllegalStateException("没有读取到本地仓，已保留原库存");
        Map<String, Object> params = Map.of("wid_list", String.join(",", warehouseIds),
                "sid_list", storeId, "inventoryOwnership", 1, "is_hide_zero_stock", 0);
        ArrayNode normalized = mapper.createArrayNode();
        Set<String> identities = new LinkedHashSet<>();
        for (JsonNode row : pages("inventory_detail_list", params)) {
            if (!row.isObject()) throw new IllegalStateException("库存明细包含无效行，已保留原库存");
            String sku = row.path("sku").asText("").trim();
            String whId = row.path("wh_id").asText("").trim();
            if (sku.isEmpty() || !warehouseIds.contains(whId) || !row.hasNonNull("good_num")) {
                throw new IllegalStateException("库存明细缺少 SKU、仓库或可用良品数量，已保留原库存");
            }
            BigDecimal good;
            try { good = new BigDecimal(row.path("good_num").asText()); }
            catch (NumberFormatException error) { throw new IllegalStateException("库存良品数量无效，已保留原库存", error); }
            if (good.signum() < 0) throw new IllegalStateException("库存良品数量为负，已保留原库存");
            String owner = row.path("store_id").asText("").trim();
            if (good.signum() > 0 && !storeId.equals(owner)) {
                throw new IllegalStateException("库存明细出现非当前店铺或未绑定店铺的有货记录，已保留原库存");
            }
            String identity = row.path("hash_id").asText("").trim();
            if (!identity.isEmpty() && !identities.add(identity)) {
                throw new IllegalStateException("库存分页包含重复明细，已保留原库存");
            }
            ObjectNode item = ((ObjectNode) row).deepCopy();
            item.put("available", good);
            normalized.add(item);
        }
        if (normalized.isEmpty()) throw new IllegalStateException("领星未返回库存明细，已保留原库存");
        ObjectNode wrapper = mapper.createObjectNode();
        ObjectNode data = wrapper.putObject("data");
        data.put("total", normalized.size());
        data.set("list", normalized);
        return wrapper;
    }

    private List<JsonNode> pages(String tool, Map<String, Object> base) {
        List<JsonNode> rows = new ArrayList<>();
        Integer expected = null;
        BigDecimal expectedGood = null;
        BigDecimal rowGood = BigDecimal.ZERO;
        for (int offset = 0; offset < MAX_ROWS; offset += PAGE_SIZE) {
            Map<String, Object> params = new LinkedHashMap<>(base);
            params.put("offset", offset);
            params.put("length", PAGE_SIZE);
            JsonNode response = call.apply(tool, params);
            JsonNode list = response.path("list");
            JsonNode total = response.path("total");
            if (!list.isArray() || !total.canConvertToInt() || total.asInt() < 0) {
                throw new IllegalStateException("领星返回的分页结构不完整，已保留原库存");
            }
            if (expected == null) expected = total.asInt();
            else if (expected != total.asInt()) throw new IllegalStateException("领星分页总数发生变化，已保留原库存");
            if ("inventory_detail_list".equals(tool)) {
                BigDecimal pageGood;
                try { pageGood = new BigDecimal(response.path("total_info").path("good_num").asText()); }
                catch (NumberFormatException error) { throw new IllegalStateException("领星未返回库存汇总，已保留原库存", error); }
                JsonNode locked = response.path("total_info").path("good_lock_num");
                if (!locked.isMissingNode() && !locked.isNull()) {
                    try {
                        if (new BigDecimal(locked.asText()).signum() != 0)
                            throw new IllegalStateException("领星出现良品锁定库存，可用库存口径待确认，已保留原库存");
                    } catch (NumberFormatException error) {
                        throw new IllegalStateException("领星锁定库存数量无效，已保留原库存", error);
                    }
                }
                if (expectedGood == null) expectedGood = pageGood;
                else if (expectedGood.compareTo(pageGood) != 0) throw new IllegalStateException("领星库存汇总在分页期间变化，已保留原库存");
                for (JsonNode item : list) {
                    try { rowGood = rowGood.add(new BigDecimal(item.path("good_num").asText())); }
                    catch (NumberFormatException error) { throw new IllegalStateException("库存明细数量无效，已保留原库存", error); }
                }
            }
            list.forEach(rows::add);
            if (rows.size() > expected) throw new IllegalStateException("领星分页行数超过总数，已保留原库存");
            if (rows.size() == expected) {
                if (expectedGood != null && expectedGood.compareTo(rowGood) != 0)
                    throw new IllegalStateException("库存明细与汇总数量不一致，已保留原库存");
                return rows;
            }
            if (list.size() != PAGE_SIZE) throw new IllegalStateException("领星分页未读取完整，已保留原库存");
        }
        throw new IllegalStateException("领星分页超过安全上限，已保留原库存");
    }
}
