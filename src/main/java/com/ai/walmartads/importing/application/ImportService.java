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
import java.util.stream.Collectors;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final WalmartAdsProperties properties;
    private final SourceScanner scanner;
    private final FileFingerprint fingerprint;
    private final FileImportProcessor processor;
    private final ImportBatchRepository batchRepository;
    private final ImportFileRepository fileRepository;

    public ImportService(
            WalmartAdsProperties properties,
            SourceScanner scanner,
            FileFingerprint fingerprint,
            FileImportProcessor processor,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository
    ) {
        this.properties = properties;
        this.scanner = scanner;
        this.fingerprint = fingerprint;
        this.processor = processor;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
    }

    public ImportRunResult scanAndImport() throws IOException {
        return scanAndImport(defaultDailySources());
    }

    public ImportRunResult scanAndImport(Set<ImportSource> requestedSources) throws IOException {
        Set<ImportSource> selectedSources = resolveSources(requestedSources);
        guardSourceRoot(selectedSources);
        List<SourceFileCandidate> candidates = scanner.scan().stream()
                .filter(candidate -> selectedSources.stream().anyMatch(source -> source.matches(candidate)))
                .toList();
        return runImport(candidates, null, selectedSources);
    }

    public ImportRunResult importUploaded(SourceFileCandidate candidate, ImportSource source) throws IOException {
        if (candidate == null || source == null || candidate.fileType() != source.fileType()) {
            throw new IllegalArgumentException("上传文件与导入类型不匹配");
        }
        ImportFileType replacementType = candidate.fileType().fullReplace() ? candidate.fileType() : null;
        return runImport(List.of(candidate), replacementType, EnumSet.of(source));
    }

    private static EnumSet<ImportSource> defaultDailySources() {
        // 默认日常扫描 = 当前真正有文件扫描链路的 6 类，显式列出，避免"隐含全集"。
        //
        // 已退役（不再进入扫描范围；ImportFileType 枚举保留，仅为读回历史 import_file 行）：
        //   • SKU_MAPPING —— WFS 上传模板。数据源换成领星 MCP 在线商品，由
        //     WalmartListingSyncService 每小时顺带写入（SkuMappingSyncService）。
        //   • HISTORY —— PNK 每日销售数据统计.xlsx（33MB）。2024-04-28 ~ 2026-07-20 的订单明细
        //     早已落库、源文件不再更新，不再每次白读 33MB 算 sha。
        //   • DEMAND_FORECAST —— source/demand-forecast/ 目录已不存在，属死链路。
        //   • ORDER —— PO_Data 每日订单明细已由领星 MCP 接管。
        return EnumSet.of(
                ImportSource.PRODUCT,
                ImportSource.RETURN_ORDER,
                ImportSource.PRODUCTION_ORDER,
                ImportSource.SHIPMENT,
                ImportSource.SHIPMENT_LOGISTICS,
                ImportSource.DOMESTIC_INVENTORY
        );
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
        return runImport(candidates, ImportFileType.RETURN_ORDER, EnumSet.of(ImportSource.RETURN_ORDER));
    }

    /**
     * 置空预检：列出本次导入会「因为源目录为空而整体置空」的来源及其当前行数。
     *
     * <p>只读，供前端在真正导入前弹一次二次确认 —— 生产订单 / 货件的明细里
     * （未完成数、质检合格数、发货数、工厂确认回货日期）只有工厂侧有，领星与沃尔玛都补不回来，
     * 而「源目录为空」有三种成因（换新版临时挪走 / 网络盘未挂载 / 确实不要了），
     * 界面上无法区分，所以把「我知道我在清」变成一个显式动作。
     */
    public List<PendingClear> preflight(Set<ImportSource> requestedSources) throws IOException {
        Set<ImportSource> selectedSources = resolveSources(requestedSources);
        guardSourceRoot(selectedSources);
        Set<ImportFileType> presentTypes = scanner.scan().stream()
                .filter(candidate -> selectedSources.stream().anyMatch(source -> source.matches(candidate)))
                .map(SourceFileCandidate::fileType)
                .collect(Collectors.toSet());
        List<PendingClear> pending = new ArrayList<>();
        for (ImportSource source : selectedSources) {
            ImportFileType type = source.fileType();
            if (!type.fullReplace() || presentTypes.contains(type)) {
                continue;
            }
            long currentRows = processor.countCurrentRows(type);
            if (currentRows > 0) {
                pending.add(new PendingClear(type.name(), currentRows));
            }
        }
        return pending;
    }

    private static Set<ImportSource> resolveSources(Set<ImportSource> requestedSources) {
        return requestedSources == null || requestedSources.isEmpty()
                ? defaultDailySources()
                : EnumSet.copyOf(requestedSources);
    }

    /**
     * 源根健康阀：只在「会置空的整类型替换来源」被选中时校验 {@code source/} 是否存在。
     *
     * <p>若源根整目录缺失（被改名 / 网络盘没挂上），生产订单、货件、物流三类会同时看起来是空的，
     * 一次扫描就把三套数据全清光。这里宁可直接拒绝，也不做这种「静默大扫除」。
     */
    private void guardSourceRoot(Set<ImportSource> selectedSources) {
        if (selectedSources.stream().noneMatch(ImportSource::fullReplace)) {
            return;
        }
        Path sourceRoot = properties.dataRoot().toAbsolutePath().normalize().resolve("source");
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException(
                    "源数据目录不存在，已中止扫描导入（避免误将生产订单/货件/物流数据置空）：" + sourceRoot
            );
        }
    }

    private ImportRunResult runImport(
            List<SourceFileCandidate> candidates,
            ImportFileType replacementType,
            Set<ImportSource> selectedSources
    ) throws IOException {
        ImportBatch batch = batchRepository.save(new ImportBatch(properties.dataRoot().toString()));

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
            if (alreadyImported(candidate, sha256, replacementType)) {
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
            try {
                DocumentImportTool.ImportOutcome processed = processor.process(file.getId(), candidate.path());
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
                failedFile.failed(candidate.path().toString(), rootMessage(exception));
                fileRepository.save(failedFile);
                results.add(FileResult.failed(candidate.path(), candidateLabel(candidate), rootMessage(exception)));
            }
        }

        List<FileResult> cleared = clearSourcesWithoutFiles(candidates, selectedSources);
        results.addAll(cleared);

        String message = "发现 %d 个文件，导入 %d，跳过 %d，失败 %d"
                .formatted(candidates.size(), imported, skipped, failed);
        // 只报「真清了行」的类型：源目录本来就空、库里也本来就空时不必刷一段噪音。
        String clearedSummary = cleared.stream()
                .filter(result -> result.importedRows() > 0)
                .map(result -> result.type() + " " + result.importedRows() + " 行")
                .collect(Collectors.joining("、"));
        if (!clearedSummary.isEmpty()) {
            message += "；源目录为空，已置空 " + clearedSummary;
        }
        if (replacementType != null && failed == 0) {
            fileRepository.activateImportedVersions(retainedFileIds, ImportFileStatus.IMPORTED);
            fileRepository.deactivateFileTypeVersionsExcept(replacementType, retainedFileIds);
        }
        batch.complete(candidates.size(), imported, skipped, failed, message);
        batchRepository.save(batch);
        return new ImportRunResult(batch.getId(), batch.getStatus().name(), message, results);
    }

    /**
     * 「源即真理」：本次被勾选、但源目录里一个文件都没有的整类型替换来源，把已有数据整体置空。
     *
     * <p>只处理<b>本次请求里显式勾选</b>的来源 —— 否则「只勾产品资料」点一次导入，
     * 会顺手把没勾的生产订单也清掉。判断按<b>类型</b>而非全局失败计数：
     * 某个类型的文件解析失败，不构成另一个类型「源里确实没有文件」的反证。
     */
    private List<FileResult> clearSourcesWithoutFiles(
            List<SourceFileCandidate> candidates,
            Set<ImportSource> selectedSources
    ) {
        List<FileResult> results = new ArrayList<>();
        for (ImportSource source : selectedSources) {
            ImportFileType type = source.fileType();
            if (!type.fullReplace()) {
                continue;
            }
            if (candidates.stream().anyMatch(candidate -> candidate.fileType() == type)) {
                continue;
            }
            int deleted = processor.clearFullReplaceType(type);
            results.add(FileResult.cleared(type, deleted));
        }
        return results;
    }

    /**
     * 判断候选文件是否无需重复导入。
     *
     * <p><b>整类型替换类</b>（生产订单 / 货件 / 物流登记）按「该类型当前是否有生效版本」判重：
     * 这类类型在源目录为空时会把明细物理删除、把版本停用（见 {@link #clearSourcesWithoutFiles}），
     * 若仍只看 {@code existsBySha256}（不看 active），把同一个文件放回源目录再扫描会被判成
     * 「内容已导入」直接跳过，数据永远回不来。
     *
     * <p><b>退货报表</b>走「整月快照」口径：只有当该报表月已存在「生效中且内容一致」的版本才跳过。
     * 若该月当前没有任何生效版本（典型场景：把文件换回曾经导入过的旧版本 —— 旧版本在
     * {@code deactivateReportVersion} 时已被停用），即使 sha 见过也必须重新导入并重新生效，
     * 否则该月会静默变成空数据，只能手工调重建端点才能救回。
     *
     * <p>其余类型沿用原有判重口径，行为不变。
     */
    private boolean alreadyImported(
            SourceFileCandidate candidate,
            String sha256,
            ImportFileType replacementType
    ) {
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
        if (candidate.fileType().fullReplace()) {
            return fileRepository.existsByFileTypeAndSha256AndActiveTrue(candidate.fileType(), sha256);
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

    /** 置空预检项：{@code fileType} 类型的现有 {@code currentRows} 行数据将被整体置空。 */
    public record PendingClear(String fileType, long currentRows) {
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

        /**
         * 该类型源目录为空，已有数据被整体置空。
         *
         * <p>{@code importedRows} 在此语义为「被清除的行数」（本类型没有落库任何行）。
         */
        static FileResult cleared(ImportFileType type, int clearedRows) {
            return new FileResult(
                    "source/" + type + "（源目录为空）",
                    type.name(),
                    "CLEARED",
                    0,
                    clearedRows,
                    "源目录为空，该类型数据已整体置空 " + clearedRows + " 行"
            );
        }
    }
}
