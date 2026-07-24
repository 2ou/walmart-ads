package com.ai.walmartads.importing.web;

import com.ai.walmartads.importing.application.FileImportProcessor;
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
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService importService;
    private final FileImportProcessor fileImportProcessor;
    private final ImportBatchRepository batchRepository;
    private final ImportFileRepository fileRepository;

    public ImportController(
            ImportService importService,
            FileImportProcessor fileImportProcessor,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository
    ) {
        this.importService = importService;
        this.fileImportProcessor = fileImportProcessor;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
    }

    @PostMapping("/scan")
    public ApiResponse<ImportService.ImportRunResult> scan() throws IOException {
        return ApiResponse.ok("扫描导入完成", importService.scanAndImport());
    }

    @PostMapping("/rebuild/return-orders")
    public ApiResponse<ImportService.ImportRunResult> rebuildReturnOrders() throws IOException {
        return ApiResponse.ok(
                "退款数据集已由本地代码工具重新构建",
                importService.replaceReturnOrders()
        );
    }

    @GetMapping("/tools")
    public ApiResponse<List<CodeToolView>> tools() {
        List<CodeToolView> result = fileImportProcessor.supportedTypes().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(type -> new CodeToolView(
                        type.name(),
                        fileImportProcessor.toolVersion(type),
                        "LOCAL_JAVA",
                        false,
                        type.name().equals("RETURN_ORDER") ? "FULL_DATASET_REPLACE" : "VERSIONED_IMPORT"
                ))
                .toList();
        return ApiResponse.ok(result);
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

    public record CodeToolView(
            String fileType,
            String version,
            String engine,
            boolean usesAi,
            String updatePolicy
    ) {
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
