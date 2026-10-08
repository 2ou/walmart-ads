package com.ai.walmartads.inventory.repository;

import com.ai.walmartads.inventory.domain.LxInventorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface LxInventorySnapshotRepository extends JpaRepository<LxInventorySnapshot, Long> {

    /** 整窗替换用：清空某一业务日的全部快照行（每日只保留最后一版）。 */
    @Modifying
    @Query("delete from LxInventorySnapshot snapshot where snapshot.businessDate = :businessDate")
    int deleteByBusinessDate(@Param("businessDate") LocalDate businessDate);

    @Query("select max(snapshot.businessDate) from LxInventorySnapshot snapshot")
    LocalDate findLatestBusinessDate();

    long countByBusinessDate(LocalDate businessDate);
}
