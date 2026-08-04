package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.DemandForecastRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DemandForecastRepository extends JpaRepository<DemandForecastRecord, Long> {

    @Query("select r from DemandForecastRecord r where r.currentVersion = true")
    List<DemandForecastRecord> findCurrentVersions();

    @Modifying
    @Query("update DemandForecastRecord r set r.currentVersion = false where r.sku in :skus")
    int deactivateCurrentVersions(@Param("skus") Collection<String> skus);
}
