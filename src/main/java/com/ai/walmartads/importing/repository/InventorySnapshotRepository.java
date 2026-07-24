package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.InventorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventorySnapshotRepository extends JpaRepository<InventorySnapshot, Long> {

    @Query(value = """
            select inventory.*
              from wm_ads_inventory_snapshot inventory
              join wm_ads_import_file file on file.id = inventory.import_file_id and file.active = true
             where inventory.item_id = :itemId
             order by inventory.snapshot_at desc, inventory.id desc
             limit 1
            """, nativeQuery = true)
    Optional<InventorySnapshot> findLatestByItemId(@Param("itemId") String itemId);
}
