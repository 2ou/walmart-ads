package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.AdCampaignItemSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AdCampaignItemSnapshotRepository extends JpaRepository<AdCampaignItemSnapshot, Long> {

    @Modifying
    @Query("update AdCampaignItemSnapshot snapshot set snapshot.currentVersion = false where snapshot.currentVersion = true")
    int deactivateAllCurrentVersions();
}
