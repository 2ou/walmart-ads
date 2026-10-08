package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ReturnOrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface ReturnOrderRecordRepository extends JpaRepository<ReturnOrderRecord, Long> {

    /**
     * 批量删除指定导入文件版本关联的全部退货明细。
     *
     * <p>用于退货报表「整月快照替换」：写入新版本前先清掉同月历史版本（含已停用版本遗留的死行）的明细，
     * 保证该月数据恒等于文件内容的精确快照，而不是靠 business_key 排名去掩盖旧数据。
     */
    @Modifying
    @Query("delete from ReturnOrderRecord r where r.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);
}
