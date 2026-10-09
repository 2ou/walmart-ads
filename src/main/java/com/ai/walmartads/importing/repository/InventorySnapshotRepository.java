package com.ai.walmartads.importing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class InventorySnapshotRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public InventorySnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 库存快照的 item_id → SKU 兜底反查，返回 {@code [sku, parent_sku]}。
     *
     * <p>已改读统一视图 {@code wm_ads_inventory_unified}（旧报表快照 ∪ 领星库存快照，V49），
     * 使 CSV 报表停用后新入库的领星快照同样可参与反查。旧表 wm_ads_inventory_snapshot 仅作为历史保留，
     * 不再直读。
     */
    public List<Object[]> findLatestByItemId(String itemId) {
        return jdbc.query("""
                select inventory.sku as sku, inventory.parent_sku as parent_sku
                  from wm_ads_inventory_unified inventory
                 where inventory.item_id = :itemId
                 order by inventory.snapshot_at desc
                 limit 1
                """, new MapSqlParameterSource("itemId", itemId),
                (rs, rowNum) -> new Object[]{rs.getString("sku"), rs.getString("parent_sku")});
    }
}
