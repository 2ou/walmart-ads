package com.ai.walmartads.importing.web;

import com.ai.walmartads.importing.application.ImportService;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService importService;
    private final ImportBatchRepository batchRepository;
    private final ImportFileRepository fileRepository;

    public ImportController(
            ImportService importService,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository
    ) {
        this.importService = importService;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
    }

    @PostMapping("/scan")
    public ApiResponse<ImportService.ImportRunResult> scan() throws IOException {
        return ApiResponse.ok("扫描导入完成", importService.scanAndImport());
    }

    @GetMapping("/batches")
    public ApiResponse<List<BatchView>> batches(@RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<BatchView> result = batchRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(BatchView::from)
                .toList();
        return ApiResponse.ok(result);
    }

    @GetMapping("/batches/{batchId}/files")
    public ApiResponse<List<FileView>> files(@PathVariable Long batchId) {
        List<FileView> result = fileRepository.findByBatchIdOrderById(batchId)
                .stream()
                .map(FileView::from)
                .toList();
        return ApiResponse.ok(result);
    }

    public record BatchView(
            Long id,
            String status,
            Object startedAt,
            Object completedAt,
            int discovered,
            int imported,
            int skipped,
            int failed,
            String message
    ) {
        static BatchView from(ImportBatch batch) {
            return new BatchView(
                    batch.getId(),
                    batch.getStatus().name(),
                    batch.getStartedAt(),
                    batch.getCompletedAt(),
                    batch.getDiscoveredFileCount(),
                    batch.getImportedFileCount(),
                    batch.getSkippedFileCount(),
                    batch.getFailedFileCount(),
                    batch.getMessage()
            );
        }
    }

    public record FileView(
            Long id,
            String name,
            String type,
            String reportKind,
            String status,
            Object reportDate,
            Object snapshotAt,
            int sourceRows,
            int importedRows,
            String error
    ) {
        static FileView from(ImportFile file) {
            return new FileView(
                    file.getId(),
                    file.getFileName(),
                    file.getFileType().name(),
                    file.getReportKind() == null ? null : file.getReportKind().name(),
                    file.getStatus().name(),
                    file.getReportDate(),
                    file.getSnapshotAt(),
                    file.getSourceRowCount(),
                    file.getImportedRowCount(),
                    file.getErrorMessage()
            );
        }
    }
}
