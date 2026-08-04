package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ItemReportRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface ItemReportRepository extends JpaRepository<ItemReportRecord, Long> {

    @Modifying
    @Query("update ItemReportRecord record set record.currentVersion = false where record.sku in :skus")
    int deactivateCurrentVersions(@Param("skus") Collection<String> skus);
}
