package com.ai.walmartads.importing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_import_file")
public class ImportFile extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    /** 关联导入批次ID */
    private ImportBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    /** 文件类型（决定用哪个解析器） */
    private ImportFileType fileType;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    /** 报表种类（细化区分，如广告的不同报表） */
    private AdReportKind reportKind;

    @Column(nullable = false, length = 500)
    /** 文件名 */
    private String fileName;

    @Column(nullable = false, length = 1200)
    /** 原始路径 */
    private String originalPath;

    @Column(length = 1200)
    /** 归档路径（可空） */
    private String archivePath;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    /** 文件 SHA256 校验值（用于去重） */
    private String sha256;

    @Column(nullable = false)
    /** 文件大小（字节） */
    private long sizeBytes;

    @Column(nullable = false)
    /** 源文件修改时间 */
    private LocalDateTime sourceModifiedAt;

    /** 报表日期（可空） */
    private LocalDate reportDate;

    /** 快照时间（可空） */
    private LocalDateTime snapshotAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 文件处理状态 */
    private ImportFileStatus status;

    @Column(nullable = false)
    /** 是否启用（1启用/0停用） */
    private boolean active;

    @Column(nullable = false)
    /** 源文件行数 */
    private int sourceRowCount;

    @Column(nullable = false)
    /** 已导入行数 */
    private int importedRowCount;

    @Column(columnDefinition = "text")
    /** 错误信息（可空） */
    private String errorMessage;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected ImportFile() {
    }

    public ImportFile(
            ImportBatch batch,
            ImportFileType fileType,
            AdReportKind reportKind,
            String fileName,
            String originalPath,
            String sha256,
            long sizeBytes,
            LocalDateTime sourceModifiedAt,
            LocalDate reportDate,
            LocalDateTime snapshotAt
    ) {
        this.batch = batch;
        this.fileType = fileType;
        this.reportKind = reportKind;
        this.fileName = fileName;
        this.originalPath = originalPath;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.sourceModifiedAt = sourceModifiedAt;
        this.reportDate = reportDate;
        this.snapshotAt = snapshotAt;
        this.status = ImportFileStatus.DISCOVERED;
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }

    public void imported(String archivePath, int sourceRows, int importedRows) {
        this.archivePath = archivePath;
        this.sourceRowCount = sourceRows;
        this.importedRowCount = importedRows;
        this.status = ImportFileStatus.IMPORTED;
    }

    public void failed(String archivePath, String message) {
        this.archivePath = archivePath;
        this.status = ImportFileStatus.FAILED;
        this.active = false;
        this.errorMessage = message;
    }

    /**
     * 「持续同步」载体复用：把本行重新指向最新一次同步的源标识与统计，**不新增** {@code import_file} 行。
     *
     * <p>供领星在线商品（{@code platform_listing_walmart_list}）同步 SKU 映射使用：该同步每小时一次，
     * 若每次都新建 import_file 行，一年将累积 8,760 行噪音记录（且 batch 表同样膨胀）。
     * 复用同一行可保持 {@code wm_ads_sku_mapping.import_file_id} 外键语义不变，
     * 也让 {@code ReturnAnalyticsService} 里 {@code join import_file on active = true} 天然成立。
     *
     * @param sourcePath       新的源标识（如 {@code lingxing://platform_listing_walmart_list}）
     * @param sourceModifiedAt 本次同步时间
     * @param sha256           本次数据集的内容指纹（用于「无变化则跳过重写」）
     * @param sourceRows       源行数
     * @param importedRows     写入行数
     */
    public void refreshFromSync(
            String sourcePath,
            LocalDateTime sourceModifiedAt,
            String sha256,
            int sourceRows,
            int importedRows
    ) {
        if (sourcePath != null && !sourcePath.isBlank()) {
            this.originalPath = sourcePath;
            this.fileName = lastSegment(sourcePath);
        }
        this.sourceModifiedAt = sourceModifiedAt;
        this.sha256 = sha256;
        this.sourceRowCount = sourceRows;
        this.importedRowCount = importedRows;
        this.status = ImportFileStatus.IMPORTED;
        this.active = true;
        this.errorMessage = null;
    }

    private static String lastSegment(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? path : name;
    }

    public Long getId() {
        return id;
    }

    public ImportBatch getBatch() {
        return batch;
    }

    public ImportFileType getFileType() {
        return fileType;
    }

    public AdReportKind getReportKind() {
        return reportKind;
    }

    public String getFileName() {
        return fileName;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public String getArchivePath() {
        return archivePath;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public LocalDateTime getSourceModifiedAt() {
        return sourceModifiedAt;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public LocalDateTime getSnapshotAt() {
        return snapshotAt;
    }

    public ImportFileStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return active;
    }

    public int getSourceRowCount() {
        return sourceRowCount;
    }

    public int getImportedRowCount() {
        return importedRowCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
