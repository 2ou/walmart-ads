package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.ImportFileStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImportFileRepository extends JpaRepository<ImportFile, Long> {

    boolean existsBySha256(String sha256);

    Optional<ImportFile> findBySha256AndFileType(String sha256, ImportFileType fileType);

    boolean existsBySha256AndFileTypeAndReportDate(
            String sha256,
            ImportFileType fileType,
            LocalDate reportDate
    );

    /**
     * 该报表月是否已存在「生效中且内容完全一致」的版本。
     *
     * <p>退货报表按整月快照口径判重：只有当该月已有 active 且 sha 相同的版本才跳过。
     * 若该月当前没有任何生效版本（例如把文件换回曾经导入过的旧版本），即使 sha 见过也必须重导，
     * 否则该月会静默变成空数据。
     */
    boolean existsByFileTypeAndReportDateAndSha256AndActiveTrue(
            ImportFileType fileType,
            LocalDate reportDate,
            String sha256
    );

    /** 取同报表月除 keepId 外所有历史版本的 id（含已停用的），用于整月快照替换时清理其明细行。 */
    @Query("""
            select file.id
              from ImportFile file
             where file.fileType = :fileType
               and file.reportDate = :reportDate
               and file.id <> :keepId
            """)
    List<Long> findOtherVersionIds(
            @Param("fileType") ImportFileType fileType,
            @Param("reportDate") LocalDate reportDate,
            @Param("keepId") Long keepId
    );

    /**
     * 取同类型除 keepId 外所有历史版本的 id（含已停用的），用于「整类型快照替换」时清理其明细行。
     *
     * <p>与 {@link #findOtherVersionIds} 的区别：HISTORY 这类报表的 {@code reportDate} 为 NULL
     * （一份文件覆盖整个历史区间），无法按报表月定位旧版本，只能按类型整体定位。
     */
    @Query("""
            select file.id
              from ImportFile file
             where file.fileType = :fileType
               and file.id <> :keepId
            """)
    List<Long> findOtherVersionIdsByType(
            @Param("fileType") ImportFileType fileType,
            @Param("keepId") Long keepId
    );

    List<ImportFile> findByBatchIdOrderById(Long batchId);

    /**
     * 取某类型当前生效的那一行，供「持续同步」写入方复用（而非每次新建）。
     *
     * <p>SKU 映射已由领星在线商品每小时同步接管，映射表恒定只有一份当前版本；
     * 复用同一行可避免 import_file / import_batch 表按小时膨胀。
     */
    Optional<ImportFile> findFirstByFileTypeAndActiveTrueOrderByIdDesc(ImportFileType fileType);

    /**
     * 取某类型在指定报表日期区间内的全部文件 id（含已停用的历史版本）。
     *
     * <p>领星订单同步按「每个日期一个 import_file」替换：重复同步同一窗口时，
     * 先把窗口内旧的领星文件连同其订单明细一并删除，再整体重写，避免 sha256 撞唯一键，
     * 也避免新旧口径的行在同一日期叠加计数。
     */
    @Query("""
            select file.id
              from ImportFile file
             where file.fileType = :fileType
               and file.reportDate between :startDate and :endDate
            """)
    List<Long> findIdsByFileTypeAndReportDateBetween(
            @Param("fileType") ImportFileType fileType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 取某类型当前生效数据里最新的报表日期（无数据时返回 null）。
     *
     * <p>用于「按数据口径」判定同步缺口：领星订单同步每天一个文件，
     * 这里返回的就是库里真正落到哪一天。只看 {@code sync_log.window_end} 会漏 ——
     * 中间任一次零星成功都会把锚点抬高，更早的窟窿就永久不可见。
     */
    @Query("""
            select max(file.reportDate)
              from ImportFile file
             where file.fileType = :fileType
               and file.active = true
            """)
    LocalDate findMaxActiveReportDate(@Param("fileType") ImportFileType fileType);

    /** 取某类型在区间内「生效数据实际覆盖」的全部报表日期，用于扫描中间空洞。 */
    @Query("""
            select file.reportDate
              from ImportFile file
             where file.fileType = :fileType
               and file.active = true
               and file.reportDate between :startDate and :endDate
            """)
    List<LocalDate> findActiveReportDates(
            @Param("fileType") ImportFileType fileType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Modifying
    @Query("""
            update ImportFile file
               set file.active = false
             where file.fileType = :fileType
               and file.reportDate = :reportDate
               and file.id <> :keepId
            """)
    int deactivateReportVersion(
            @Param("fileType") ImportFileType fileType,
            @Param("reportDate") LocalDate reportDate,
            @Param("keepId") Long keepId
    );

    @Modifying
    @Query("""
            update ImportFile file
               set file.active = false
             where file.fileType = :fileType
               and file.snapshotAt = :snapshotAt
               and file.id <> :keepId
            """)
    int deactivateInventorySnapshotVersion(
            @Param("fileType") ImportFileType fileType,
            @Param("snapshotAt") LocalDateTime snapshotAt,
            @Param("keepId") Long keepId
    );

    @Modifying
    @Query("""
            update ImportFile file
               set file.active = false
             where file.reportKind = :reportKind
               and file.reportDate = :reportDate
               and file.id <> :keepId
            """)
    int deactivateAdReportVersion(
            @Param("reportKind") AdReportKind reportKind,
            @Param("reportDate") LocalDate reportDate,
            @Param("keepId") Long keepId
    );

    @Modifying
    @Query("""
            update ImportFile file
               set file.active = false
             where file.fileType = :fileType
               and file.id <> :keepId
            """)
    int deactivateFileTypeVersions(
            @Param("fileType") ImportFileType fileType,
            @Param("keepId") Long keepId
    );

    @Modifying
    @Transactional
    @Query("""
            update ImportFile file
               set file.active = true
             where file.id in :keepIds
               and file.status = :status
            """)
    int activateImportedVersions(
            @Param("keepIds") Collection<Long> keepIds,
            @Param("status") ImportFileStatus status
    );

    @Modifying
    @Transactional
    @Query("""
            update ImportFile file
               set file.active = false
             where file.fileType = :fileType
               and file.id not in :keepIds
            """)
    int deactivateFileTypeVersionsExcept(
            @Param("fileType") ImportFileType fileType,
            @Param("keepIds") Collection<Long> keepIds
    );
}
