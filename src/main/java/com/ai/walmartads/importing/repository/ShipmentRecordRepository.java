package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ShipmentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;

public interface ShipmentRecordRepository extends JpaRepository<ShipmentRecord, Long> {

    @Query("select r from ShipmentRecord r where r.currentVersion = true")
    List<ShipmentRecord> findCurrentVersions();

    @Query("select max(r.syncedAt) from ShipmentRecord r where r.currentVersion = true and r.sourceType = :sourceType")
    LocalDateTime findLatestCurrentSyncedAtBySourceType(@Param("sourceType") String sourceType);

    @Modifying
    @Query("update ShipmentRecord r set r.currentVersion = false where r.currentVersion = true")
    int deactivateAllCurrentVersions();

    /**
     * 批量删除指定导入文件版本关联的全部发货明细。
     *
     * <p>配合「整类型快照替换」使用：仅 {@link #deactivateAllCurrentVersions()} 会把旧行标记为
     * currentVersion=false 而把行留在库里，每重导一次就累积一份完整副本 —— 实测本表曾滞留
     * 4,704 行 stale（占全表 5,290 行的 89%）。
     */
    @Modifying
    @Query("delete from ShipmentRecord r where r.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);
}
