package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ProductionOrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductionOrderRepository extends JpaRepository<ProductionOrderRecord, Long> {

    @Query("select r from ProductionOrderRecord r where r.currentVersion = true")
    List<ProductionOrderRecord> findCurrentVersions();

    @Modifying
    @Query("update ProductionOrderRecord r set r.currentVersion = false where r.currentVersion = true")
    int deactivateAllCurrentVersions();

    /**
     * 批量删除指定导入文件版本关联的全部生产订单明细。
     *
     * <p>与 {@link #deactivateAllCurrentVersions()} 互补：后者只改标记不删行，本方法负责物理回收，
     * 否则每重导一次都追加一份完整副本（实测曾滞留 626 行 stale / 684 行全表，倍率 10.8x）。
     */
    @Modifying
    @Query("delete from ProductionOrderRecord r where r.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);
}
