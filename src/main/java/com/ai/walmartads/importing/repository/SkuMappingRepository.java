package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.SkuMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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
     * Advertising SKU resolution now happens during LingXing ingestion and does not update legacy ad rows.
     * 始终能命中本次导入的新映射，不会因清理而取空。
     */
    @Modifying
    @Query("delete from SkuMapping mapping where mapping.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);

    /**
     * 物理清空整表，供「持续同步」写入方复用单条 import_file 行时使用。
     *
     * <p>文件扫描时代的清理依赖 {@link #deleteByImportFileIdIn}（按旧版本 import_file 定位）；
     * 领星同步复用同一行，旧版本号不再变化，该清理永远不会触发 —— 若沿用
     * {@link #deactivateCurrentVersions()} 只改标记，每小时会再堆一层 1,020 行死数据。
     * 故同步写入方走「先清空、再整窗写入」，保证全表恒等于当前在线商品集合。
     */
    @Modifying
    @Query("delete from SkuMapping mapping")
    int deleteAllRows();

    /**
     * 批量取 item_id → parent_sku（仅当前生效版本）。
     *
     * <p>评价 / 广告等按 Walmart Item ID 归款的场景用：一次查询替代 N 次
     * {@link #findFirstByItemIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc}，
     * 避免 200+ Item ID 时的 N+1。同 item_id 多行时 DISTINCT 收敛为任意一条。
     *
     * @return 每个元素为 {@code [itemId, parentSku]}
     */
    @Query("select distinct mapping.itemId, mapping.parentSku from SkuMapping mapping "
            + "where mapping.currentVersion = true and mapping.itemId in :itemIds")
    List<Object[]> findCurrentParentSkuByItemIds(@Param("itemIds") Collection<String> itemIds);
}
