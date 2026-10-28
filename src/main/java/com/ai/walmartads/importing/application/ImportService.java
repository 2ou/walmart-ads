package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileStatus;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final FileFingerprint fingerprint;
    private final FileImportProcessor processor;
    private final ImportBatchRepository batchRepository;
    private final ImportFileRepository fileRepository;

    public ImportService(
            FileFingerprint fingerprint,
            FileImportProcessor processor,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository
    ) {
        this.fingerprint = fingerprint;
        this.processor = processor;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
    }

    /**
     * Imports one browser upload from request memory. Only its fingerprint, audit metadata and parsed rows
     * are persisted; the original workbook is not copied to an application-local directory.
     */
    public ImportRunResult importUploaded(
            SourceFileCandidate candidate,
            ImportSource source,
            byte[] content
    ) throws IOException {
        if (candidate == null || source == null || candidate.fileType() != source.fileType()) {
            throw new IllegalArgumentException("上传文件与导入类型不匹配");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }

        String name = candidate.path().getFileName().toString();
        String sourceReference = "database://workspace-upload/" + name;
        ImportBatch batch = batchRepository.save(new ImportBatch("database://workspace-upload"));
        ImportFileType replacementType = candidate.fileType().fullReplace() ? candidate.fileType() : null;
        List<FileResult> results = new ArrayList<>();
        List<Long> retainedFileIds = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        String sha256 = processor.importFingerprint(candidate.fileType(), fingerprint.sha256(content));
        ImportFile existingReplacement = replacementType == null
                ? null
                : fileRepository.findBySha256AndFileType(sha256, candidate.fileType()).orElse(null);
        if (existingReplacement != null && existingReplacement.getStatus() == ImportFileStatus.IMPORTED) {
            retainedFileIds.add(existingReplacement.getId());
            skipped = 1;
            results.add(FileResult.reused(candidate.path(), candidateLabel(candidate)));
        } else if (existingReplacement != null) {
            failed = 1;
            results.add(FileResult.failed(candidate.path(), candidateLabel(candidate),
                    "The same document previously failed. Change the file content before rebuilding."));
        } else if (alreadyImported(candidate, sha256, replacementType)) {
            skipped = 1;
            results.add(FileResult.skipped(candidate.path(), candidateLabel(candidate)));
        } else {
            ImportFile file = new ImportFile(
                    batch,
                    candidate.fileType(),
                    candidate.reportKind(),
                    name,
                    sourceReference,
                    sha256,
                    content.length,
                    LocalDateTime.now(),
                    candidate.reportDate(),
                    candidate.snapshotAt()
            );
            fileRepository.saveAndFlush(file);
            try {
                DocumentImportTool.ImportOutcome processed =
                        processor.processUploaded(file.getId(), content, sourceReference);
                retainedFileIds.add(file.getId());
                imported = 1;
                results.add(FileResult.imported(candidate.path(), candidateLabel(candidate),
                        processed.sourceRows(), processed.importedRows()));
            } catch (Exception exception) {
                failed = 1;
                log.error("Memory upload import failed for {}", name, exception);
                ImportFile failedFile = fileRepository.findById(file.getId()).orElse(file);
                failedFile.failed(sourceReference, rootMessage(exception));
                fileRepository.save(failedFile);
                results.add(FileResult.failed(candidate.path(), candidateLabel(candidate), rootMessage(exception)));
            }
        }

        if (replacementType != null && failed == 0 && !retainedFileIds.isEmpty()) {
            fileRepository.activateImportedVersions(retainedFileIds, ImportFileStatus.IMPORTED);
            fileRepository.deactivateFileTypeVersionsExcept(replacementType, retainedFileIds);
        }
        String message = "发现 1 个文件，导入 %d，跳过 %d，失败 %d"
                .formatted(imported, skipped, failed);
        batch.complete(1, imported, skipped, failed, message);
        batchRepository.save(batch);
        return new ImportRunResult(batch.getId(), batch.getStatus().name(), message, results);
    }

    private boolean alreadyImported(
            SourceFileCandidate candidate,
            String sha256,
            ImportFileType replacementType
    ) {
        // Product workbooks are confirmed full-catalog snapshots. Reusing an old hash would skip a valid
        // restore/retire operation when the current catalog has changed since that file was last imported.
        if (candidate.fileType() == ImportFileType.PRODUCT) {
            return false;
        }
        if (replacementType != null) {
            return false;
        }
        if (candidate.fileType() == ImportFileType.RETURN_ORDER) {
            return fileRepository.existsByFileTypeAndReportDateAndSha256AndActiveTrue(
                    candidate.fileType(),
                    candidate.reportDate(),
                    sha256
            );
        }
        return fileRepository.existsBySha256(sha256);
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
