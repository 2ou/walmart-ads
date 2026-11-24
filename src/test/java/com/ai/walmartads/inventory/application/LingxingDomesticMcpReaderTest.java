package com.ai.walmartads.inventory.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LingxingDomesticMcpReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsAllPagesWithStoreAndDomesticWarehouseFiltersAndUsesGoodStock() {
        List<Map<String, Object>> calls = new ArrayList<>();
        var reader = new LingxingDomesticMcpReader(mapper, (tool, params) -> {
            calls.add(params);
            if (tool.equals("warehouse_realtime_inventory_list")) return warehouse();
            assertThat(params).containsEntry("sid_list", "123").containsEntry("wid_list", "746")
                    .containsEntry("inventoryOwnership", 1);
            return inventoryPage((int) params.get("offset"), 201, 201, "123", false);
        });
        JsonNode result = reader.read("123");
        assertThat(result.path("data").path("total").asInt()).isEqualTo(201);
        assertThat(result.path("data").path("list").get(0).path("available").asInt()).isEqualTo(1);
        assertThat(calls).hasSize(3);
        assertThat(calls.getFirst().get("type")).isEqualTo(List.of("1"));
        assertThat(calls.getLast().get("offset")).isEqualTo(200);
    }

    @Test
    void rejectsDifferentStoreOrChangedTotalOrDuplicateRows() {
        for (String problem : List.of("owner", "total", "duplicate")) {
            BiFunction<String, Map<String, Object>, JsonNode> call = (tool, params) -> {
                if (tool.equals("warehouse_realtime_inventory_list")) return warehouse();
                int offset = (int) params.get("offset");
                ObjectNode page = inventoryPage(offset, 201, problem.equals("total") && offset > 0 ? 200 : 201,
                        problem.equals("owner") ? "other" : "123", problem.equals("duplicate"));
                return page;
            };
            assertThatThrownBy(() -> new LingxingDomesticMcpReader(mapper, call).read("123"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("已保留原库存");
        }
    }

    @Test
    void rejectsMismatchingSummaryAndIncompletePage() {
        for (String problem : List.of("summary", "partial", "locked")) {
            var reader = new LingxingDomesticMcpReader(mapper, (tool, params) -> {
                if (tool.equals("warehouse_realtime_inventory_list")) return warehouse();
                ObjectNode page = inventoryPage((int) params.get("offset"), 201, 201, "123", false);
                if (problem.equals("summary")) page.withObject("total_info").put("good_num", 202);
                else if (problem.equals("locked")) page.withObject("total_info").put("good_lock_num", 1);
                else if (((int) params.get("offset")) == 0) page.withArray("list").remove(0);
                return page;
            });
            assertThatThrownBy(() -> reader.read("123")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已保留原库存");
        }
    }

    private ObjectNode warehouse() {
        ObjectNode response = mapper.createObjectNode();
        response.put("total", 1);
        response.putArray("list").addObject().put("wh_id", 746);
        return response;
    }

    private ObjectNode inventoryPage(int offset, int rows, int total, String owner, boolean duplicate) {
        ObjectNode response = mapper.createObjectNode();
        response.put("total", total);
        response.putObject("total_info").put("good_num", rows);
        var list = response.putArray("list");
        for (int i = offset; i < Math.min(offset + 200, rows); i++) {
            ObjectNode row = list.addObject();
            row.put("sku", "PNK-" + i).put("wh_id", 746).put("store_id", owner)
                    .put("hash_id", duplicate ? "same" : "id" + i).put("good_num", 1)
                    .put("good_lock_num", 50);
        }
        return response;
    }
}
