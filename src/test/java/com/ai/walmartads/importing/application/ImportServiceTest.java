package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「源即真理」口径：整类型替换来源（生产订单 / 货件 / 物流登记）在源目录为空时须整体置空。
 *
 * <p>背景：{@code FileImportProcessor} 里的「整类型作废」只在真拿到文件时触发，源目录空了
 * 一次都不会跑 —— 上一次导入的明细会永远以 {@code current_version = true} 挂着，
 * 做货量 / 在途量一直按陈旧值计算（实测滞留 85 行做货）。
 */
class ImportServiceTest {

    @TempDir
    Path temp;

    private Path dataRoot;
    private SourceScanner scanner;
    private FileFingerprint fingerprint;
    private FileImportProcessor processor;
    private ImportFileRepository fileRepository;
    private ImportService service;

    @BeforeEach
    void setUp() throws IOException {
        dataRoot = Files.createDirectories(temp.resolve("data"));
        Files.createDirectories(dataRoot.resolve("source"));
        scanner = mock(SourceScanner.class);
        processor = mock(FileImportProcessor.class);
        fileRepository = mock(ImportFileRepository.class);
        // 指纹必须给出非 null 值：否则 importFingerprint(type, null) 会绕开 anyString() 桩，
        // 让整条导入链路走进「新文件」分支，把「判重」这件事测没了。
        fingerprint = mock(FileFingerprint.class);
        when(fingerprint.sha256(any())).thenReturn("content-sha");
        service = buildService(dataRoot);
    }

    private ImportService buildService(Path root) throws IOException {
        ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
        when(batchRepository.save(any(ImportBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new ImportService(
                new WalmartAdsProperties(root, temp.resolve("archive"), null, null, null),
                scanner,
                fingerprint,
                processor,
                batchRepository,
                fileRepository
        );
    }

    @Test
    void clearsFullReplaceSourceWhoseDirectoryHoldsNoFile() throws Exception {
        when(scanner.scan()).thenReturn(List.of());
        when(processor.clearFullReplaceType(ImportFileType.PRODUCTION_ORDER)).thenReturn(85);

        ImportService.ImportRunResult result = service.scanAndImport(EnumSet.of(ImportSource.PRODUCTION_ORDER));

        verify(processor).clearFullReplaceType(ImportFileType.PRODUCTION_ORDER);
        assertThat(result.message())
                .contains("已置空")
                .contains("PRODUCTION_ORDER 85 行");
        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.status()).isEqualTo("CLEARED");
            assertThat(file.importedRows()).isEqualTo(85);
        });
    }

    @Test
    void keepsFullReplaceSourceThatStillHasAFile() throws Exception {
        Path workbook = Files.createFile(dataRoot.resolve("source").resolve("生产中订单.xlsx"));
        when(scanner.scan()).thenReturn(List.of(candidate(workbook, ImportFileType.PRODUCTION_ORDER)));
        when(processor.importFingerprint(any(), anyString())).thenReturn("sha-po");
        when(processor.process(any(), any())).thenReturn(new DocumentImportTool.ImportOutcome(1, 1));

        ImportService.ImportRunResult result = service.scanAndImport(EnumSet.of(ImportSource.PRODUCTION_ORDER));

        assertThat(result.files()).singleElement()
                .satisfies(file -> assertThat(file.status()).isEqualTo("IMPORTED"));
        verify(processor, never()).clearFullReplaceType(any());
    }

    @Test
    void ignoresFullReplaceSourcesThatWereNotSelected() throws Exception {
        // 只勾了产品资料与国内仓库存 ⇒ 即便生产订单源目录是空的，也不得顺手把它清掉。
        when(scanner.scan()).thenReturn(List.of());

        service.scanAndImport(EnumSet.of(ImportSource.PRODUCT, ImportSource.DOMESTIC_INVENTORY));

        verify(processor, never()).clearFullReplaceType(any());
    }

    @Test
    void fallsBackToDefaultDailySourcesWhenNothingSelected() throws Exception {
        when(scanner.scan()).thenReturn(List.of());

        service.scanAndImport(null);

        // 默认 6 类含 3 个整类型替换来源 ⇒ 源目录为空时全部置空。
        verify(processor).clearFullReplaceType(ImportFileType.PRODUCTION_ORDER);
        verify(processor).clearFullReplaceType(ImportFileType.SHIPMENT);
        verify(processor).clearFullReplaceType(ImportFileType.SHIPMENT_LOGISTICS);
    }

    @Test
    void refusesToScanMissingSourceRootInsteadOfClearingEverything() throws Exception {
        // source/ 整目录缺失（改名 / 网络盘未挂载）时三类会同时「看起来是空的」，
        // 这里必须直接拒绝，而不是静默把做货、货件、物流全清光。
        Path bareRoot = Files.createDirectories(temp.resolve("bare"));
        ImportService bare = buildService(bareRoot);
        when(scanner.scan()).thenReturn(List.of());

        assertThatThrownBy(() -> bare.scanAndImport(EnumSet.of(ImportSource.PRODUCTION_ORDER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("源数据目录不存在");
        assertThatThrownBy(() -> bare.preflight(EnumSet.of(ImportSource.SHIPMENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("源数据目录不存在");
        verify(processor, never()).clearFullReplaceType(any());
    }

    @Test
    void preflightListsOnlyEmptyFullReplaceSourcesThatStillHoldRows() throws Exception {
        when(scanner.scan()).thenReturn(List.of());
        when(processor.countCurrentRows(ImportFileType.PRODUCTION_ORDER)).thenReturn(85L);
        when(processor.countCurrentRows(ImportFileType.SHIPMENT)).thenReturn(0L);

        List<ImportService.PendingClear> pending = service.preflight(EnumSet.of(
                ImportSource.PRODUCTION_ORDER,
                ImportSource.SHIPMENT,
                ImportSource.DOMESTIC_INVENTORY
        ));

        assertThat(pending).singleElement().satisfies(item -> {
            assertThat(item.fileType()).isEqualTo("PRODUCTION_ORDER");
            assertThat(item.currentRows()).isEqualTo(85L);
        });
        // 快照类永不在预检范围内。
        verify(processor, never()).countCurrentRows(ImportFileType.DOMESTIC_INVENTORY);
    }

    @Test
    void judgesFullReplaceDedupByActiveVersionSoRestoredFilesReimport() throws Exception {
        // 置空会把明细删掉、版本停用。若判重仍只看 sha，把同一个文件放回源目录会被判成
        // 「内容已导入」直接跳过，数据永远回不来 —— 所以这几类必须按 active 判重。
        Path workbook = Files.createFile(dataRoot.resolve("source").resolve("货件.xlsx"));
        when(scanner.scan()).thenReturn(List.of(candidate(workbook, ImportFileType.SHIPMENT)));
        when(processor.importFingerprint(any(), anyString())).thenReturn("sha-ship");
        when(fileRepository.existsByFileTypeAndSha256AndActiveTrue(ImportFileType.SHIPMENT, "sha-ship"))
                .thenReturn(true);

        ImportService.ImportRunResult result = service.scanAndImport(EnumSet.of(ImportSource.SHIPMENT));

        assertThat(result.files()).singleElement()
                .satisfies(file -> assertThat(file.status()).isEqualTo("SKIPPED"));
        verify(fileRepository, never()).existsBySha256(anyString());
        verify(processor, never()).clearFullReplaceType(any());
    }

    @Test
    void keepsContentOnlyDedupForSnapshotTypes() throws Exception {
        Path workbook = Files.createFile(dataRoot.resolve("source").resolve("库存明细.xlsx"));
        when(scanner.scan()).thenReturn(List.of(candidate(workbook, ImportFileType.DOMESTIC_INVENTORY)));
        when(processor.importFingerprint(any(), anyString())).thenReturn("sha-domestic");
        when(fileRepository.existsBySha256("sha-domestic")).thenReturn(true);

        ImportService.ImportRunResult result = service.scanAndImport(EnumSet.of(ImportSource.DOMESTIC_INVENTORY));

        assertThat(result.files()).singleElement()
                .satisfies(file -> assertThat(file.status()).isEqualTo("SKIPPED"));
        verify(fileRepository, never()).existsByFileTypeAndSha256AndActiveTrue(any(), anyString());
    }

    private static SourceFileCandidate candidate(Path path, ImportFileType type) {
        return new SourceFileCandidate(
                path,
                Path.of("source").resolve(path.getFileName()),
                type,
                null,
                null,
                null
        );
    }
}
