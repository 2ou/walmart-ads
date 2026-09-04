package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ShipmentLogisticsRepository extends JpaRepository<ShipmentLogisticsRecord, Long> {

    @Query("select r from ShipmentLogisticsRecord r where r.currentVersion = true")
    List<ShipmentLogisticsRecord> findCurrentVersions();

    @Modifying
    @Query("update ShipmentLogisticsRecord r set r.currentVersion = false where r.currentVersion = true")
    int deactivateAllCurrentVersions();

    /**
     * 批量删除指定导入文件版本关联的全部物流明细。
     *
     * <p>与 {@link #deactivateAllCurrentVersions()} 互补：后者只改标记不删行，本方法负责物理回收，
     * 否则每重导一次都追加一份完整副本（实测曾滞留 142 行 stale / 183 行全表）。
     */
    @Modifying
    @Query("delete from ShipmentLogisticsRecord r where r.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);
}
