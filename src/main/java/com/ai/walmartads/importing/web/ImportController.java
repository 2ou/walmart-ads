package com.ai.walmartads.importing.web;

import com.ai.walmartads.importing.application.FileImportProcessor;
import com.ai.walmartads.importing.application.ImportSource;
import com.ai.walmartads.importing.application.ImportService;
import com.ai.walmartads.importing.application.SourceFileCandidate;
import com.ai.walmartads.importing.application.SourceScanner;
import com.ai.walmartads.config.WalmartAdsProperties;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspaceOwnershipService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService importService;
    private final FileImportProcessor fileImportProcessor;
    private final ImportBatchRepository batchRepository;
    private final ImportFileRepository fileRepository;
    private final SourceScanner sourceScanner;
    private final WalmartAdsProperties properties;
    @Autowired(required = false)
    private WorkspaceOwnershipService ownershipService;

    @Autowired
    public ImportController(
            ImportService importService,
            FileImportProcessor fileImportProcessor,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository,
            SourceScanner sourceScanner,
            WalmartAdsProperties properties
    ) {
        this.importService = importService;
        this.fileImportProcessor = fileImportProcessor;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
        this.sourceScanner = sourceScanner;
        this.properties = properties;
    }

    ImportController(
            ImportService importService,
            FileImportProcessor fileImportProcessor,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository
    ) {
        this(importService, fileImportProcessor, batchRepository, fileRepository, null, null);
    }

    @PostMapping("/scan")
    public ApiResponse<ImportService.ImportRunResult> scan(
            @RequestParam(name = "source", required = false) Set<ImportSource> sources
    ) throws IOException {
        throw new IllegalArgumentException("共享目录扫描已停用，请在当前账号的数据导入页上传文件");
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<ImportService.ImportRunResult> upload(
            @RequestPart("source") String sourceValue,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        ImportSource source;
        try {
            source = ImportSource.valueOf(sourceValue == null ? "" : sourceValue.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("未知导入类型");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的工作簿");
        }
        String original = file.getOriginalFilename();
        String safeName = original == null ? "upload.xlsx" : Path.of(original).getFileName().toString();
        if (!safeName.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 工作簿");
        }
        Path ownerRoot = properties.dataRoot().toAbsolutePath().normalize()
                .resolve("workspace-uploads").resolve(String.valueOf(WorkspaceContext.requireId()));
        Files.createDirectories(ownerRoot);
        Path tempDirectory = Files.createTempDirectory(ownerRoot, "upload-");
        Path tempFile = tempDirectory.resolve(safeName).normalize();
        if (!tempFile.startsWith(tempDirectory)) {
            throw new IllegalArgumentException("文件名无效");
        }
        try {
            file.transferTo(tempFile);
            if (ownershipService != null) {
                ownershipService.verifyUploadedWorkbook(tempFile, source, safeName);
            }
            SourceFileCandidate candidate = sourceScanner.uploadedCandidate(tempFile, source);
            return ApiResponse.ok("当前工作区文件导入完成", importService.importUploaded(candidate, source));
        } finally {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDirectory);
        }
    }

    /**
     * 置空预检：本次导入会因「源目录为空」而整体置空哪些来源、各多少行。
     *
     * <p>只读接口，供前端在实际导入前弹二次确认。返回空数组表示没有东西会被清掉。
     */
    @GetMapping("/preflight")
    public ApiResponse<List<ImportService.PendingClear>> preflight(
            @RequestParam(name = "source", required = false) Set<ImportSource> sources
    ) throws IOException {
        throw new IllegalArgumentException("共享目录扫描已停用");
    }

    @PostMapping("/rebuild/return-orders")
    public ApiResponse<ImportService.ImportRunResult> rebuildReturnOrders() throws IOException {
        throw new IllegalArgumentException("共享目录扫描已停用，请上传当前账号的退款文件");
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
                        type.name().equals("RETURN_ORDER") || type.fullReplace()
                                ? "FULL_DATASET_REPLACE"
                                : "VERSIONED_IMPORT"
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
