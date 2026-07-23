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
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ImportBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImportFileType fileType;

    @Column(nullable = false, length = 500)
    private String fileName;

    @Column(nullable = false, length = 1200)
    private String originalPath;

    @Column(length = 1200)
    private String archivePath;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private LocalDateTime sourceModifiedAt;

    private LocalDate reportDate;

    private LocalDateTime snapshotAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportFileStatus status;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int sourceRowCount;

    @Column(nullable = false)
    private int importedRowCount;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected ImportFile() {
    }

    public ImportFile(
            ImportBatch batch,
            ImportFileType fileType,
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
