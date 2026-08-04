package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShipmentLogisticsRepository extends JpaRepository<ShipmentLogisticsRecord, Long> {

    @Query("select r from ShipmentLogisticsRecord r where r.currentVersion = true")
    List<ShipmentLogisticsRecord> findCurrentVersions();

    @Modifying
    @Query("update ShipmentLogisticsRecord r set r.currentVersion = false where r.currentVersion = true")
    int deactivateAllCurrentVersions();
}
