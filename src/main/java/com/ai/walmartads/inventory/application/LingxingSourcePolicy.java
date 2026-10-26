package com.ai.walmartads.inventory.application;

import java.time.Duration;
import java.time.LocalDateTime;

/** 项目内置领星同步与人工文件兜底之间的统一数据源优先级。 */
public final class LingxingSourcePolicy {

    /** 每天 08:00 同步；36 小时内仍视为有效的自动快照。 */
    public static final Duration AUTOMATIC_FRESHNESS = Duration.ofHours(36);

    private LingxingSourcePolicy() {
    }

    /**
     * 默认使用自动快照；用户显式上传了更新文件时立即接管，下一次自动同步成功后再切回。
     */
    public static boolean preferAutomatic(
            LocalDateTime automaticAt,
            LocalDateTime uploadAt
    ) {
        return preferAutomatic(automaticAt, uploadAt, LocalDateTime.now());
    }

    public static boolean preferAutomatic(
            LocalDateTime automaticAt,
            LocalDateTime uploadAt,
            LocalDateTime now
    ) {
        if (automaticAt == null) return false;
        return uploadAt == null || !uploadAt.isAfter(automaticAt);
    }

    public static boolean isAutomaticFresh(LocalDateTime automaticAt) {
        return isAutomaticFresh(automaticAt, LocalDateTime.now());
    }

    public static boolean isAutomaticFresh(LocalDateTime automaticAt, LocalDateTime now) {
        return automaticAt != null && !automaticAt.isBefore(now.minus(AUTOMATIC_FRESHNESS));
    }
}
