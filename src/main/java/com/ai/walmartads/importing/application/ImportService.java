package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileStatus;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.parser.ImportValues;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final WalmartAdsProperties properties;
    private final SourceScanner scanner;
    private final FileFingerprint fingerprint;
    private final FileArchiveService archiveService;
    private final FileImportProcessor processor;
    private final ImportBatchRepository batchRepository;
    private final ImportFileRepository fileRepository;

    public ImportService(
            WalmartAdsProperties properties,
            SourceScanner scanner,
            FileFingerprint fingerprint,
            FileArchiveService archiveService,
            FileImportProcessor processor,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository
    ) {
        this.properties = properties;
        this.scanner = scanner;
        this.fingerprint = fingerprint;
        this.archiveService = archiveService;
        this.processor = processor;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
    }

    public ImportRunResult scanAndImport() throws IOException {
        return scanAndImport(EnumSet.allOf(ImportSource.class));
    }

    public ImportRunResult scanAndImport(Set<ImportSource> requestedSources) throws IOException {
        Set<ImportSource> selectedSources = requestedSources == null || requestedSources.isEmpty()
                ? EnumSet.allOf(ImportSource.class)
                : EnumSet.copyOf(requestedSources);
        List<SourceFileCandidate> candidates = scanner.scan().stream()
                .filter(candidate -> selectedSources.stream().anyMatch(source -> source.matches(candidate)))
                .toList();
        return runImport(candidates, null);
    }

    public ImportRunResult replaceReturnOrders() throws IOException {
        List<SourceFileCandidate> candidates = scanner.scan().stream()
                .filter(candidate -> candidate.fileType() == ImportFileType.RETURN_ORDER)
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No canonical return workbooks found under source/Return_Orders"
            );
        }
        return runImport(candidates, ImportFileType.RETURN_ORDER);
    }

    private ImportRunResult runImport(
            List<SourceFileCandidate> candidates,
            ImportFileType replacementType
    ) throws IOException {
        ImportBatch batch = batchRepository.save(new ImportBatch(properties.dataRoot().toString()));
        Path batchDirectory = archiveService.batchDirectory(batch.getId());
        batch.setArchiveDirectory(batchDirectory.toString());
        batchRepository.save(batch);

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<FileResult> results = new ArrayList<>();
        List<Long> retainedFileIds = new ArrayList<>();

        for (SourceFileCandidate candidate : candidates) {
            String sha256;
            try {
                sha256 = processor.importFingerprint(
                        candidate.fileType(),
                        fingerprint.sha256(candidate.path())
                );
            } catch (Exception exception) {
                failed++;
                results.add(FileResult.failed(candidate.path(), candidate.fileType().name(), exception.getMessage()));
                continue;
            }
            ImportFile existingReplacement = replacementType == null
                    ? null
                    : fileRepository.findBySha256AndFileType(sha256, candidate.fileType()).orElse(null);
            if (existingReplacement != null
                    && existingReplacement.getStatus() == ImportFileStatus.IMPORTED) {
                retainedFileIds.add(existingReplacement.getId());
                skipped++;
                results.add(FileResult.reused(candidate.path(), candidateLabel(candidate)));
                continue;
            }
            if (existingReplacement != null) {
                failed++;
                results.add(FileResult.failed(
                        candidate.path(),
                        candidateLabel(candidate),
                        "The same document previously failed. Change the file content before rebuilding."
                ));
                continue;
            }
            boolean alreadyImported = replacementType == null && (
                    candidate.fileType() == ImportFileType.WEEKLY_INVENTORY
                            ? fileRepository.existsBySha256AndFileTypeAndReportDate(
                                    sha256,
                                    candidate.fileType(),
                                    candidate.reportDate()
                            )
                            : fileRepository.existsBySha256(sha256)
            );
            if (alreadyImported) {
                skipped++;
                results.add(FileResult.skipped(candidate.path(), candidateLabel(candidate)));
                continue;
            }
            ImportFile file = new ImportFile(
                    batch,
                    candidate.fileType(),
                    candidate.reportKind(),
                    candidate.path().getFileName().toString(),
                    candidate.path().toString(),
                    sha256,
                    Files.size(candidate.path()),
                    ImportValues.fileTime(Files.getLastModifiedTime(candidate.path()).toInstant()),
                    candidate.reportDate(),
                    candidate.snapshotAt()
            );
            fileRepository.saveAndFlush(file);
            Path archivePath = null;
            try {
                archivePath = archiveService.archive(batchDirectory, candidate);
                DocumentImportTool.ImportOutcome processed = processor.process(file.getId(), archivePath);
                retainedFileIds.add(file.getId());
                imported++;
                results.add(FileResult.imported(
                        candidate.path(),
                        candidateLabel(candidate),
                        processed.sourceRows(),
                        processed.importedRows()
                ));
            } catch (Exception exception) {
                failed++;
                log.error("Import failed for {}", candidate.path(), exception);
                ImportFile failedFile = fileRepository.findById(file.getId()).orElse(file);
                failedFile.failed(archivePath == null ? null : archivePath.toString(), rootMessage(exception));
                fileRepository.save(failedFile);
                results.add(FileResult.failed(candidate.path(), candidateLabel(candidate), rootMessage(exception)));
            }
        }

        String message = "发现 %d 个文件，导入 %d，跳过 %d，失败 %d"
                .formatted(candidates.size(), imported, skipped, failed);
        if (replacementType != null && failed == 0) {
            fileRepository.activateImportedVersions(retainedFileIds, ImportFileStatus.IMPORTED);
            fileRepository.deactivateFileTypeVersionsExcept(replacementType, retainedFileIds);
        }
        batch.complete(candidates.size(), imported, skipped, failed, message);
        batchRepository.save(batch);
        return new ImportRunResult(batch.getId(), batch.getStatus().name(), message, results);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String candidateLabel(SourceFileCandidate candidate) {
        return candidate.reportKind() == null
                ? candidate.fileType().name()
                : candidate.reportKind().name();
    }

    public record ImportRunResult(Long batchId, String status, String message, List<FileResult> files) {
    }

    public record FileResult(
            String path,
            String type,
            String status,
            int sourceRows,
            int importedRows,
            String message
    ) {
        static FileResult imported(Path path, String type, int sourceRows, int importedRows) {
            return new FileResult(path.toString(), type, "IMPORTED", sourceRows, importedRows, null);
        }

        static FileResult skipped(Path path, String type) {
            return new FileResult(path.toString(), type, "SKIPPED", 0, 0, "文件内容已导入");
        }

        static FileResult reused(Path path, String type) {
            return new FileResult(
                    path.toString(),
                    type,
                    "REUSED",
                    0,
                    0,
                    "已复用相同内容的代码解析结果"
            );
        }

        static FileResult failed(Path path, String type, String message) {
            return new FileResult(path.toString(), type, "FAILED", 0, 0, message);
        }
    }
}
