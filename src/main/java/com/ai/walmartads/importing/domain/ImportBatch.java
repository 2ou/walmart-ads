package com.ai.walmartads.importing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportBatchStatus status;

    @Column(nullable = false, length = 1000)
    private String sourceRoot;

    @Column(length = 1000)
    private String archiveDirectory;

    @Column(nullable = false)
    private int discoveredFileCount;

    @Column(nullable = false)
    private int importedFileCount;

    @Column(nullable = false)
    private int skippedFileCount;

    @Column(nullable = false)
    private int failedFileCount;

    @Column(columnDefinition = "text")
    private String message;

    protected ImportBatch() {
    }

    public ImportBatch(String sourceRoot) {
        this.startedAt = LocalDateTime.now();
        this.status = ImportBatchStatus.RUNNING;
        this.sourceRoot = sourceRoot;
    }

    public void setArchiveDirectory(String archiveDirectory) {
        this.archiveDirectory = archiveDirectory;
    }

    public void complete(int discovered, int imported, int skipped, int failed, String message) {
        this.completedAt = LocalDateTime.now();
        this.discoveredFileCount = discovered;
        this.importedFileCount = imported;
        this.skippedFileCount = skipped;
        this.failedFileCount = failed;
        this.message = message;
        this.status = failed == 0 ? ImportBatchStatus.SUCCESS
                : imported > 0 || skipped > 0 ? ImportBatchStatus.PARTIAL : ImportBatchStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public ImportBatchStatus getStatus() {
        return status;
    }

    public String getSourceRoot() {
        return sourceRoot;
    }

    public String getArchiveDirectory() {
        return archiveDirectory;
    }

    public int getDiscoveredFileCount() {
        return discoveredFileCount;
    }

    public int getImportedFileCount() {
        return importedFileCount;
    }

    public int getSkippedFileCount() {
        return skippedFileCount;
    }

    public int getFailedFileCount() {
        return failedFileCount;
    }

    public String getMessage() {
        return message;
    }
}
