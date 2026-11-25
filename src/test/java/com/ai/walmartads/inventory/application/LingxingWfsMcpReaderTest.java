package com.ai.walmartads.inventory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LingxingWfsMcpReaderTest {
    private static final long SAMPLE_STORE_ID = 900000000000000001L;
    private static final String SAMPLE_STORE = Long.toString(SAMPLE_STORE_ID);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsTwoPagesAndAlwaysFiltersExactStore() {
        var offsets = new ArrayList<Integer>();
        var reader = new LingxingWfsMcpReader(mapper, (tool, params) -> {
            assertThat(tool).isEqualTo("wfs_cargo_list");
            assertThat(params.get("storeIdList")).isEqualTo(List.of(SAMPLE_STORE));
            int offset = (int) params.get("offset");
            offsets.add(offset);
            return page(offset, 101, Math.min(100, 101 - offset));
        });
        assertThat(reader.read(SAMPLE_STORE).size()).isEqualTo(101);
        assertThat(offsets).containsExactly(0, 100);
    }

    @Test
    void refusesFalseCompletenessAndDuplicatePage() {
        var incomplete = new LingxingWfsMcpReader(mapper, (tool, params) -> page(0, 101, 99));
        assertThatThrownBy(() -> incomplete.read(SAMPLE_STORE)).hasMessageContaining("分页未读取完整");
        var duplicate = new LingxingWfsMcpReader(mapper, (tool, params) -> {
            int offset = (int) params.get("offset");
            return page(0, 101, offset == 0 ? 100 : 1).put("offset", offset);
        });
        assertThatThrownBy(() -> duplicate.read(SAMPLE_STORE)).hasMessageContaining("重复记录");
    }

    @Test
    void refusesOtherStoreRowsAndEmptySnapshots() {
        var parentMismatch = new LingxingWfsMcpReader(mapper, (tool, params) -> page(0, 1, 1));
        assertThatThrownBy(() -> parentMismatch.read("other")).hasMessageContaining("其他店铺");
        var childMismatch = new LingxingWfsMcpReader(mapper, (tool, params) -> {
            var response = page(0, 1, 1);
            ((ObjectNode) response.path("list").get(0)).putArray("cargoListGoodsDetailResponses")
                    .addObject().put("storeId", "other");
            return response;
        });
        assertThatThrownBy(() -> childMismatch.read(SAMPLE_STORE)).hasMessageContaining("明细不属于当前店铺");
        var empty = new LingxingWfsMcpReader(mapper, (tool, params) -> page(0, 0, 0));
        assertThatThrownBy(() -> empty.read(SAMPLE_STORE)).hasMessageContaining("空货件列表");
    }

    private ObjectNode page(int offset, int total, int count) {
        var page = mapper.createObjectNode().put("offset", offset).put("total", Integer.toString(total));
        var rows = page.putArray("list");
        for (int i = offset; i < offset + count; i++) {
            rows.addObject().put("id", i + 1).put("cargoCode", "C" + i).put("storeId", SAMPLE_STORE_ID);
        }
        return page;
    }
}
