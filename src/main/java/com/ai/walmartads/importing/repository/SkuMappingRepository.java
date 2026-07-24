package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.SkuMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SkuMappingRepository extends JpaRepository<SkuMapping, Long> {

    Optional<SkuMapping> findFirstByItemIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(String itemId);

    @Modifying
    @Query("update SkuMapping mapping set mapping.currentVersion = false where mapping.currentVersion = true")
    int deactivateCurrentVersions();
}
