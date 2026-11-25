package com.ai.walmartads.inventory.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Read a full, single-store snapshot from the MCP WFS cargo list. */
final class LingxingWfsMcpReader {
    static final String TOOL_ID = "wfs_cargo_list";
    static final int PAGE_SIZE = 100;
    private static final int MAX_ROWS = 20_000;

    private final ObjectMapper mapper;
    private final BiFunction<String, Map<String, Object>, JsonNode> call;

    LingxingWfsMcpReader(ObjectMapper mapper, BiFunction<String, Map<String, Object>, JsonNode> call) {
        this.mapper = mapper;
        this.call = call;
    }

    ArrayNode read(String storeId) {
        if (storeId == null || storeId.isBlank()) throw rejected("缺少领星店铺 ID");
        ArrayNode rows = mapper.createArrayNode();
        Set<String> ids = new HashSet<>();
        Set<String> cargoCodes = new HashSet<>();
        int expected = -1;
        for (int offset = 0; offset < MAX_ROWS; offset += PAGE_SIZE) {
            JsonNode response = call.apply(TOOL_ID, Map.of(
                    "offset", offset, "length", PAGE_SIZE, "storeIdList", List.of(storeId)));
            if (response == null || !response.isObject() || !response.path("list").isArray()) {
                throw rejected("分页响应缺少货件列表");
            }
            int total = requiredInt(response.path("total"), "分页总数");
            if (total == 0) throw rejected("领星返回空货件列表");
            if (total > MAX_ROWS) throw rejected("货件分页超过安全上限");
            if (expected == -1) expected = total;
            else if (expected != total) throw rejected("分页期间货件总数变化");
            JsonNode reportedOffset = response.path("offset");
            if (!reportedOffset.isMissingNode() && !reportedOffset.isNull()
                    && offset != requiredInt(reportedOffset, "分页偏移")) {
                throw rejected("领星返回了错误的页码");
            }
            JsonNode page = response.path("list");
            if (page.size() != Math.min(PAGE_SIZE, expected - offset)) throw rejected("货件分页未读取完整");
            for (JsonNode item : page) {
                if (!item.isObject() || !storeId.equals(field(item, "storeId"))) {
                    throw rejected("货件列表包含其他店铺或店铺归属不明的数据");
                }
                String id = field(item, "id");
                String cargoCode = field(item, "cargoCode");
                if (id.isEmpty() || !ids.add(id) || cargoCode.isEmpty() || !cargoCodes.add(cargoCode)) {
                    throw rejected("货件编号缺失或分页包含重复记录");
                }
                for (JsonNode detail : item.path("cargoListGoodsDetailResponses")) {
                    String owner = field(detail, "storeId");
                    if (!owner.isEmpty() && !storeId.equals(owner)) throw rejected("货件 SKU 明细不属于当前店铺");
                }
                rows.add(item);
            }
            if (rows.size() == expected) return rows;
        }
        throw rejected("未能读取完整货件快照");
    }

    private static int requiredInt(JsonNode value, String label) {
        try {
            String text = value.asText("");
            if ((value.isTextual() || value.isIntegralNumber()) && text.matches("[0-9]+")) return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            // Reject malformed counts; never coerce them to zero.
        }
        throw rejected("货件" + label + "无效");
    }

    private static String field(JsonNode row, String key) {
        return row.path(key).asText("").trim();
    }

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException("领星 WFS " + reason + "，已保留原货件数据");
    }
}
