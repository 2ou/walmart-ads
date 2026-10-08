package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.InventorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InventorySnapshotRepository extends JpaRepository<InventorySnapshot, Long> {

    /**
     * 库存快照的 item_id → SKU 兜底反查，返回 {@code [sku, parent_sku]}。
     *
     * <p>已改读统一视图 {@code wm_ads_inventory_unified}（旧报表快照 ∪ 领星库存快照，V49），
     * 使 CSV 报表停用后新入库的领星快照同样可参与反查。旧表 wm_ads_inventory_snapshot 仅作为历史保留，
     * 不再直读。
     */
    @Query(value = """
            select inventory.sku as sku, inventory.parent_sku as parent_sku
              from wm_ads_inventory_unified inventory
             where inventory.item_id = :itemId
             order by inventory.snapshot_at desc
             limit 1
            """, nativeQuery = true)
    List<Object[]> findLatestByItemId(@Param("itemId") String itemId);
}
