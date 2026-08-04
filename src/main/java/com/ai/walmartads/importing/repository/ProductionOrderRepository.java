package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ProductionOrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductionOrderRepository extends JpaRepository<ProductionOrderRecord, Long> {

    @Query("select r from ProductionOrderRecord r where r.currentVersion = true")
    List<ProductionOrderRecord> findCurrentVersions();

    @Modifying
    @Query("update ProductionOrderRecord r set r.currentVersion = false where r.currentVersion = true")
    int deactivateAllCurrentVersions();
}
