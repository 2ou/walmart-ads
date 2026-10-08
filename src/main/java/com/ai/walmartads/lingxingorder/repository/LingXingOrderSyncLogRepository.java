package com.ai.walmartads.lingxingorder.repository;

import com.ai.walmartads.lingxingorder.domain.LingXingOrderSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface LingXingOrderSyncLogRepository extends JpaRepository<LingXingOrderSyncLog, Long> {

    Optional<LingXingOrderSyncLog> findTopByOrderByStartedAtDesc();

    /**
     * 最近一次成功同步的窗口结束日（无成功记录时返回 null）。
     *
     * <p>用于定时同步的「补窗」：若上次成功窗口已被时间甩在后面（服务停机 / 同步连续失败），
     * 本次窗口要向前扩展把缺口盖住。PO_Data 已停用，漏掉的日期没有第二来源可补。
     */
    @Query("select max(l.windowEnd) from LingXingOrderSyncLog l where l.status = 'SUCCESS'")
    LocalDate findLatestSuccessWindowEnd();
}
