package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.SkuMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface SkuMappingRepository extends JpaRepository<SkuMapping, Long> {

    Optional<SkuMapping> findFirstByItemIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(String itemId);

    @Modifying
    @Query("update SkuMapping mapping set mapping.currentVersion = false where mapping.currentVersion = true")
    int deactivateCurrentVersions();

    /**
     * 批量删除指定导入文件版本关联的全部 SKU 映射明细。
     *
     * <p>与 {@link #deactivateCurrentVersions()} 互补：后者只改标记不删行，本方法负责物理回收，
     * 否则每重导一次都追加一份完整副本（实测曾滞留 4,058 行 stale / 4,960 行全表）。
     *
     * <p>删除发生在 {@code saveAllAndFlush} 之前，故随后执行的
     * {@code AdPerformanceRepository.backfillSkuFromCurrentMappings()}（只读 currentVersion=true 行）
     * 始终能命中本次导入的新映射，不会因清理而取空。
     */
    @Modifying
    @Query("delete from SkuMapping mapping where mapping.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);
}
