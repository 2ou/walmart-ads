package com.ai.walmartads.walmartcomment.repository;

import com.ai.walmartads.walmartcomment.domain.WalmartCommentSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WalmartCommentSyncLogRepository extends JpaRepository<WalmartCommentSyncLog, Long> {

    Optional<WalmartCommentSyncLog> findTopByOrderByStartedAtDesc();

    /** 最近 20 次同步记录，供前端展示同步历史。 */
    List<WalmartCommentSyncLog> findTop20ByOrderByStartedAtDesc();

    Optional<WalmartCommentSyncLog> findTopByStatusAndTriggeredByOrderByStartedAtDesc(
            String status, String triggeredBy);

    /**
     * 最近一次成功同步的窗口结束日（无成功记录时返回 null）。
     *
     * <p>用于定时同步的「补窗」：若上次成功窗口已被时间甩在后面（服务停机 / 同步连续失败），
     * 本次窗口要向前扩展把缺口盖住。
     */
    @Query("select max(l.windowEnd) from WalmartCommentSyncLog l where l.status = 'SUCCESS'")
    LocalDate findLatestSuccessWindowEnd();
}
