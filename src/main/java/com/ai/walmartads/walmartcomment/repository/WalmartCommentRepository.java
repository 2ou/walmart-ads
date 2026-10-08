package com.ai.walmartads.walmartcomment.repository;

import com.ai.walmartads.walmartcomment.domain.WalmartComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 评价明细仓储。写入走「按 (platform, comment_id) upsert」，故提供两个精确读取入口：
 * {@link #findByPlatformAndCommentIdIn}（批量 upsert 前的存在性判定）与
 * {@link #findByPlatformAndCommentDateBetweenOrderByCommentDateDesc}（窗口回读）。
 */
public interface WalmartCommentRepository extends JpaRepository<WalmartComment, Long> {

    Optional<WalmartComment> findByPlatformAndCommentId(String platform, String commentId);

    List<WalmartComment> findByPlatformAndCommentIdIn(String platform, List<String> commentIds);

    /** 窗口内已有评价（含未归款），用于同步前判定「插入 / 更新」。 */
    List<WalmartComment> findByPlatformAndCommentDateBetweenOrderByCommentDateDesc(
            String platform, LocalDate start, LocalDate end);

    /** 全部评价（按日期倒序），供口碑模块聚合 —— 全表约千行量级，直接内存聚合。 */
    List<WalmartComment> findByPlatformOrderByCommentDateDesc(String platform);

    /** 全部未归款的评价 —— 款号映射表重导后回填 parentSku 用。 */
    List<WalmartComment> findByPlatformAndParentSkuIsNull(String platform);

    long countByPlatform(String platform);

    long countByPlatformAndParentSkuIsNull(String platform);

    /**
     * 数据覆盖边界（回填完成度核对用）。
     *
     * <p>不能用方法名派生：派生查询只能选实体 / 投影，选不出聚合值
     * （实测 {@code Specified result type [LocalDate] did not match Query selection type [WalmartComment]}），
     * 故显式写 JPQL。
     */
    @Query("select max(c.commentDate) from WalmartComment c where c.platform = :platform")
    Optional<LocalDate> findMaxCommentDateByPlatform(@Param("platform") String platform);

    /** 最近一次写入时间（前端「数据截至」用）。 */
    Optional<WalmartComment> findTopByPlatformOrderBySyncedAtDesc(String platform);
}
