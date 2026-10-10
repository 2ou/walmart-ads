package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 固化「默认扫描范围」口径。
 *
 * <p>HISTORY（PNK 每日销售数据统计.xlsx，33MB）与 DEMAND_FORECAST 均已退出文件扫描链路
 * （2026-09-28）：前者历史订单早已落库、源文件不再更新，后者源目录已不存在。
 * SKU_MAPPING 同日换源为领星 MCP 在线商品（不再走文件导入）。
 * 即便扫描器仍产出对应候选，也不会进入默认导入范围。
 */
class ImportServiceDefaultScopeTest {

    @TempDir
    Path temp;

    @Test
    void retiredSkuMappingWorkbookIsNotScannedByDefault() throws Exception {
        ImportService service = serviceWithCandidates(candidate(ImportFileType.SKU_MAPPING));

        ImportService.ImportRunResult result = service.scanAndImport();

        assertThat(result.message()).contains("发现 0 个文件");
    }

    @Test
    void retiredHistoryWorkbookIsNotScannedByDefault() throws Exception {
        ImportService service = serviceWithCandidates(candidate(ImportFileType.HISTORY));

        ImportService.ImportRunResult result = service.scanAndImport();

        assertThat(result.message()).contains("发现 0 个文件");
    }

    @Test
    void retiredDemandForecastWorkbookIsNotScannedByDefault() throws Exception {
        ImportService service = serviceWithCandidates(candidate(ImportFileType.DEMAND_FORECAST));

        ImportService.ImportRunResult result = service.scanAndImport();

        assertThat(result.message()).contains("发现 0 个文件");
    }

    @Test
    void ordinarySourcesAreStillScannedByDefault() throws Exception {
        ImportService service = serviceWithCandidates(candidate(ImportFileType.PRODUCT));

        ImportService.ImportRunResult result = service.scanAndImport();

        assertThat(result.message()).contains("发现 1 个文件").contains("跳过 1");
    }

    @Test
    void domesticInventoryIsScannedByDefault() throws Exception {
        ImportService service = serviceWithCandidates(candidate(ImportFileType.DOMESTIC_INVENTORY));

        ImportService.ImportRunResult result = service.scanAndImport();

        assertThat(result.message()).contains("发现 1 个文件").contains("跳过 1");
    }

    private ImportService serviceWithCandidates(SourceFileCandidate... candidates) throws Exception {
        // 默认范围含 3 个「整类型替换」来源，源根健康阀要求 source/ 存在 —— 用临时目录而不是 "."。
        Files.createDirectories(temp.resolve("source"));

        WalmartAdsProperties properties = mock(WalmartAdsProperties.class);
        when(properties.dataRoot()).thenReturn(temp);

        SourceScanner scanner = mock(SourceScanner.class);
        when(scanner.scan()).thenReturn(List.of(candidates));

        FileFingerprint fingerprint = mock(FileFingerprint.class);
        when(fingerprint.sha256(any(java.nio.file.Path.class))).thenReturn("sha-test");

        FileImportProcessor processor = mock(FileImportProcessor.class);
        when(processor.importFingerprint(any(), anyString())).thenReturn("sha-test");

        ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
        when(batchRepository.save(any(ImportBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ImportFileRepository fileRepository = mock(ImportFileRepository.class);
        when(fileRepository.existsBySha256(anyString())).thenReturn(true);

        return new ImportService(
                properties,
                scanner,
                fingerprint,
                processor,
                batchRepository,
                fileRepository
        );
    }

    private static SourceFileCandidate candidate(ImportFileType type) {
        return new SourceFileCandidate(
                Path.of(type.name() + ".xlsx"),
                Path.of(type.name() + ".xlsx"),
                type,
                null,
                LocalDate.of(2026, 7, 22),
                null
        );
    }
}
