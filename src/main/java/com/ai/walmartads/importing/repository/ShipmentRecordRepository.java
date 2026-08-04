package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ShipmentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShipmentRecordRepository extends JpaRepository<ShipmentRecord, Long> {

    @Query("select r from ShipmentRecord r where r.currentVersion = true")
    List<ShipmentRecord> findCurrentVersions();

    @Modifying
    @Query("update ShipmentRecord r set r.currentVersion = false where r.currentVersion = true")
    int deactivateAllCurrentVersions();
}
