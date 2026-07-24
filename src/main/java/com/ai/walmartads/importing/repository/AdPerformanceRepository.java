package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.AdPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AdPerformanceRepository extends JpaRepository<AdPerformance, Long> {

    @Modifying
    @Query(value = """
            update wm_ads_ad_performance ads
            join wm_ads_sku_mapping mapping
              on mapping.item_id = ads.item_id
             and mapping.current_version = true
               set ads.sku = mapping.sku,
                   ads.parent_sku = mapping.parent_sku
             where ads.sku is null or trim(ads.sku) = ''
            """, nativeQuery = true)
    int backfillSkuFromCurrentMappings();
}
