package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.AdPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

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

    /**
     * 批量删除指定导入文件版本关联的全部广告明细。
     *
     * <p>用于 HISTORY 报表「整类型快照替换」：写入新版本前先清掉历史版本（含已停用版本遗留的死行）的明细，
     * 避免每次重导都追加一份完整副本。此前 process() 只调用 {@code deactivateFileTypeVersions} 把旧
     * import_file 置 active=0，从不删明细，导致 6 次导入累积出 5 份逐字段完全相同的 20 万行副本。
     */
    @Modifying
    @Query("delete from AdPerformance a where a.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);
}
