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
public class ImportFile {

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
