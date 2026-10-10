package com.ai.walmartads.importing.web;

import com.ai.walmartads.importing.application.FileImportProcessor;
import com.ai.walmartads.importing.application.ImportSource;
import com.ai.walmartads.importing.application.ImportService;
import com.ai.walmartads.importing.application.LocalImportPathService;
import com.ai.walmartads.importing.application.SourceFileCandidate;
import com.ai.walmartads.importing.application.SourceScanner;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import com.ai.walmartads.workspace.WorkspaceOwnershipService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final LocalImportPathService localImportPathService;
    @Autowired(required = false)
    private WorkspaceOwnershipService ownershipService;

    @Autowired
    public ImportController(
            ImportService importService,
            FileImportProcessor fileImportProcessor,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository,
            SourceScanner sourceScanner,
            LocalImportPathService localImportPathService
    ) {
        this.importService = importService;
        this.fileImportProcessor = fileImportProcessor;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
        this.sourceScanner = sourceScanner;
        this.localImportPathService = localImportPathService;
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
        return ApiResponse.ok(importService.scanAndImport(sources, localImportPathService.currentRoot()));
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<ImportService.ImportRunResult> upload(
            @RequestParam("source") List<String> sourceValues,
            @RequestParam("file") List<MultipartFile> files
    ) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的工作簿");
        }
        if (sourceValues == null || sourceValues.size() != files.size()) {
            throw new IllegalArgumentException("导入类型数量与文件数量不匹配");
        }
        List<ImportService.FileResult> allFiles = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        Long firstBatchId = null;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            try {
                ImportService.ImportRunResult one = importOne(sourceValues.get(i), files.get(i));
                if (firstBatchId == null) firstBatchId = one.batchId();
                allFiles.addAll(one.files());
                ok++;
            } catch (Exception exception) {
                failed++;
                String name = files.get(i).getOriginalFilename();
                detail.append(String.format("「%s」失败：%s；", name == null ? "未命名文件" : name, exception.getMessage()));
            }
        }
        String message = String.format("批量导入完成：成功 %d 个文件，失败 %d 个。%s", ok, failed, detail);
        return ApiResponse.ok(message, new ImportService.ImportRunResult(firstBatchId, "OK", message, allFiles));
    }

    private ImportService.ImportRunResult importOne(String sourceValue, MultipartFile file) throws IOException {
        ImportSource source;
        try {
            source = ImportSource.valueOf(sourceValue == null ? "" : sourceValue.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("未知导入类型：" + sourceValue);
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("存在空文件，请重新选择");
        }
        String original = file.getOriginalFilename();
        String safeName = original == null ? "upload.xlsx" : Path.of(original).getFileName().toString();
        if (!safeName.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 工作簿：" + safeName);
        }
        byte[] content = file.getBytes();
        if (ownershipService != null) {
            ownershipService.verifyUploadedWorkbook(content, source, safeName);
        }
        SourceFileCandidate candidate = sourceScanner.uploadedCandidate(safeName, source, LocalDateTime.now());
        return importService.importUploaded(candidate, source, content);
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
        return ApiResponse.ok(importService.preflight(sources, localImportPathService.currentRoot()));
    }

    @PostMapping("/rebuild/return-orders")
    public ApiResponse<ImportService.ImportRunResult> rebuildReturnOrders() throws IOException {
        return ApiResponse.ok(importService.replaceReturnOrders(localImportPathService.currentRoot()));
    }

    @GetMapping("/local-source")
    public ApiResponse<LocalSourceView> localSource() {
        return ApiResponse.ok(LocalSourceView.from(localImportPathService.current()));
    }

    @PutMapping("/local-source")
    public ApiResponse<LocalSourceView> updateLocalSource(@RequestBody LocalSourceUpdate request) {
        if (request == null) {
            throw new IllegalArgumentException("请输入本地扫描路径");
        }
        return ApiResponse.ok("本地扫描路径已保存",
                LocalSourceView.from(localImportPathService.save(request.path())));
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

    public record LocalSourceUpdate(String path) {
    }

    public record LocalSourceView(String sourceRoot, String displayPath, boolean exists) {
        static LocalSourceView from(LocalImportPathService.Setting setting) {
            return new LocalSourceView(setting.sourceRoot().toString(), setting.displayPath(), setting.exists());
        }
    }
}
