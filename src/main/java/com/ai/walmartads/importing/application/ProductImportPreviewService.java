package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.parser.ParsedRows;
import com.ai.walmartads.importing.parser.ProductWorkbookParser;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds short-lived product workbook previews in process memory until the user confirms the diff. */
@Service
public class ProductImportPreviewService {

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    private final ProductWorkbookParser productParser;
    private final ProductImportPlanner planner;
    private final ImportService importService;
    private final UploadCandidateFactory candidateFactory;
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final Map<Long, Object> workspaceLocks = new ConcurrentHashMap<>();

    public ProductImportPreviewService(
            ProductWorkbookParser productParser,
            ProductImportPlanner planner,
            ImportService importService,
            UploadCandidateFactory candidateFactory
    ) {
        this.productParser = productParser;
        this.planner = planner;
        this.importService = importService;
        this.candidateFactory = candidateFactory;
    }

    public Preview preview(String fileName, byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }
        cleanupExpired();
        long workspaceId = WorkspaceContext.requireId();
        LocalDateTime now = LocalDateTime.now();
        ImportFile previewFile = new ImportFile(
                new ImportBatch("memory://product-preview"),
                ImportFileType.PRODUCT,
                null,
                fileName,
                "memory://product-preview/" + fileName,
                "0".repeat(64),
                content.length,
                now,
                null,
                null
        );
        ParsedRows<ProductRecord> parsed = productParser.parse(content, previewFile);
        ProductImportPlanner.Plan plan = planner.plan(parsed);
        LocalDateTime expiresAt = now.plus(PREVIEW_TTL);
        String token = null;
        boolean canConfirm = plan.conflicts().isEmpty() && plan.uniqueSkuCount() > 0;
        if (canConfirm) {
            token = UUID.randomUUID().toString();
            drafts.put(token, new Draft(workspaceId, fileName, content.clone(), now,
                    expiresAt, plan.baselineFingerprint()));
        }
        return toPreview(token, fileName, expiresAt, canConfirm, plan);
    }

    public ConfirmResult confirm(String token) throws IOException {
        cleanupExpired();
        long workspaceId = WorkspaceContext.requireId();
        Draft draft = drafts.get(token);
        if (draft == null || draft.workspaceId() != workspaceId) {
            throw new IllegalArgumentException("产品导入预览不存在或已过期，请重新上传");
        }
        Object lock = workspaceLocks.computeIfAbsent(workspaceId, ignored -> new Object());
        synchronized (lock) {
            draft = drafts.get(token);
            if (draft == null || draft.expiresAt().isBefore(LocalDateTime.now())) {
                drafts.remove(token);
                throw new IllegalArgumentException("产品导入预览已过期，请重新上传");
            }
            String currentFingerprint = planner.currentCatalogFingerprint();
            if (!draft.baselineFingerprint().equals(currentFingerprint)) {
                drafts.remove(token);
                throw new IllegalStateException("产品库在预览后已发生变化，请重新上传并确认最新差异");
            }
            SourceFileCandidate candidate = candidateFactory.create(
                    draft.fileName(), ImportSource.PRODUCT, draft.createdAt());
            try {
                ImportService.ImportRunResult result = importService.importUploaded(
                        candidate, ImportSource.PRODUCT, draft.content());
                if ("FAILED".equals(result.status())) {
                    throw new IllegalStateException(result.message());
                }
                return new ConfirmResult(result.batchId(), result.status(), result.message());
            } finally {
                drafts.remove(token);
            }
        }
    }

    public void discard(String token) {
        Draft draft = drafts.get(token);
        if (draft != null && draft.workspaceId() == WorkspaceContext.requireId()) {
            drafts.remove(token);
        }
    }

    private Preview toPreview(
            String token,
            String fileName,
            LocalDateTime expiresAt,
            boolean canConfirm,
            ProductImportPlanner.Plan plan
    ) {
        List<ProductImportPlanner.PlannedChange> visibleChanges = plan.changes().stream()
                .filter(change -> change.type() != ProductImportPlanner.ChangeType.UNCHANGED)
                .toList();
        List<ProductImportPlanner.PlannedChange> additions = visibleChanges.stream()
                .filter(change -> change.type() == ProductImportPlanner.ChangeType.ADD)
                .toList();
        List<ProductImportPlanner.PlannedChange> updates = visibleChanges.stream()
                .filter(change -> change.type() == ProductImportPlanner.ChangeType.UPDATE)
                .toList();
        List<ProductImportPlanner.PlannedChange> retirements = visibleChanges.stream()
                .filter(change -> change.type() == ProductImportPlanner.ChangeType.RETIRE)
                .toList();
        Summary summary = new Summary(
                plan.sourceRows(), plan.uniqueSkuCount(),
                additions.size(), styleCount(additions),
                updates.size(), styleCount(updates),
                retirements.size(), styleCount(retirements),
                additions.stream().filter(ProductImportPlanner.PlannedChange::restored).count(),
                retirements.stream().filter(ProductImportPlanner.PlannedChange::wholeStyle)
                        .map(ProductImportPlanner.PlannedChange::parentSku).distinct().count(),
                plan.count(ProductImportPlanner.ChangeType.UNCHANGED),
                plan.conflicts().size()
        );
        return new Preview(token, fileName, expiresAt, canConfirm, summary,
                additions, updates, retirements, plan.conflicts());
    }

    private static long styleCount(List<ProductImportPlanner.PlannedChange> rows) {
        return rows.stream().map(ProductImportPlanner.PlannedChange::parentSku)
                .filter(value -> value != null && !value.isBlank()).distinct().count();
    }

    private void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        new ArrayList<>(drafts.entrySet()).stream()
                .filter(entry -> entry.getValue().expiresAt().isBefore(now))
                .map(Map.Entry::getKey)
                .sorted(Comparator.naturalOrder())
                .forEach(drafts::remove);
    }

    private record Draft(
            long workspaceId,
            String fileName,
            byte[] content,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            String baselineFingerprint
    ) { }

    public record Summary(
            int sourceRows,
            int uniqueSkuCount,
            long addedSkuCount,
            long addedStyleCount,
            long updatedSkuCount,
            long updatedStyleCount,
            long retiredSkuCount,
            long retiredStyleCount,
            long restoredSkuCount,
            long wholeStyleRetiredCount,
            long unchangedSkuCount,
            long conflictSkuCount
    ) { }

    public record Preview(
            String token,
            String fileName,
            LocalDateTime expiresAt,
            boolean canConfirm,
            Summary summary,
            List<ProductImportPlanner.PlannedChange> additions,
            List<ProductImportPlanner.PlannedChange> updates,
            List<ProductImportPlanner.PlannedChange> retirements,
            List<ProductImportPlanner.Conflict> conflicts
    ) { }

    public record ConfirmResult(Long batchId, String status, String message) { }
}
