package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceScannerTest {

    @TempDir
    Path temp;

    @Test
    void scansDailyReportsFromDateDirectoryAndDistinguishesReportKind() throws Exception {
        Path day = Files.createDirectories(temp.resolve("date").resolve("2026-7-22"));
        Files.createFile(day.resolve("ItemPerformance_example.csv"));
        Files.createFile(day.resolve("ItemKeywordPerformance_example.csv"));
        Files.createFile(day.resolve("KeywordPerformance_example.csv"));
        Files.createFile(day.resolve("PO_Data_example.xlsx"));

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                temp, temp.resolve("archive"), null, null, null, null
        ));
        List<SourceFileCandidate> candidates = scanner.scan();

        assertThat(candidates).hasSize(4);
        assertThat(report(candidates, "ItemPerformance_example.csv").fileType())
                .isEqualTo(ImportFileType.AD_REPORT);
        assertThat(report(candidates, "ItemPerformance_example.csv").reportKind())
                .isEqualTo(AdReportKind.ITEM_PERFORMANCE);
        assertThat(report(candidates, "ItemKeywordPerformance_example.csv").reportKind())
                .isEqualTo(AdReportKind.ITEM_KEYWORD_PERFORMANCE);
        assertThat(report(candidates, "KeywordPerformance_example.csv").reportKind())
                .isEqualTo(AdReportKind.KEYWORD_PERFORMANCE);
        assertThat(report(candidates, "PO_Data_example.xlsx").reportKind()).isNull();
        assertThat(report(candidates, "PO_Data_example.xlsx").relativePath())
                .isEqualTo(Path.of("date", "2026-7-22", "PO_Data_example.xlsx"));
    }

    @Test
    void fallsBackToLegacyRootDateDirectoryWhenDateFolderIsAbsent() throws Exception {
        Path day = Files.createDirectories(temp.resolve("2026-7-21"));
        Files.createFile(day.resolve("PO_Data_legacy.xlsx"));

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                temp, temp.resolve("archive"), null, null, null, null
        ));

        assertThat(scanner.scan())
                .singleElement()
                .extracting(SourceFileCandidate::reportDate)
                .isEqualTo(java.time.LocalDate.of(2026, 7, 21));
    }

    @Test
    void scansConfiguredHistoricalWorkbookAsItemPerformanceHistory() throws Exception {
        Path dataRoot = Files.createDirectories(temp.resolve("data"));
        Path workbook = Files.createFile(temp.resolve("PNK 每日销售数据统计.xlsx"));
        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot,
                temp.resolve("archive"),
                workbook,
                java.time.LocalDate.of(2026, 7, 21),
                null,
                null
        ));

        assertThat(scanner.scan())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.fileType()).isEqualTo(ImportFileType.HISTORY);
                    assertThat(candidate.reportKind()).isEqualTo(AdReportKind.ITEM_PERFORMANCE);
                    assertThat(candidate.relativePath())
                            .isEqualTo(Path.of("history", "PNK 每日销售数据统计.xlsx"));
                });
    }

    @Test
    void scansConfiguredSkuMappingWorkbookBeforeDailyReports() throws Exception {
        Path dataRoot = Files.createDirectories(temp.resolve("mapping-data"));
        Path day = Files.createDirectories(dataRoot.resolve("date").resolve("2026-7-22"));
        Files.createFile(day.resolve("ItemPerformance_example.csv"));
        Path workbook = Files.createFile(temp.resolve("WFS-upload-template.xlsx"));
        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot,
                temp.resolve("mapping-archive"),
                null,
                null,
                workbook,
                null
        ));

        assertThat(scanner.scan())
                .extracting(SourceFileCandidate::fileType)
                .containsExactly(ImportFileType.SKU_MAPPING, ImportFileType.AD_REPORT);
    }

    @Test
    void scansWeeklyInventoryRecursivelyUsingDateFromWorkbookName() throws Exception {
        Path dataRoot = Files.createDirectories(temp.resolve("weekly-data"));
        Path weeklyRoot = Files.createDirectories(temp.resolve("weekly").resolve("2025年"));
        Files.createFile(weeklyRoot.resolve("沃尔玛PNK周报 2025.04.28 .xlsx"));
        Files.createFile(weeklyRoot.resolve("沃尔玛PNK周转天数 2025.04.28.xlsx"));

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot,
                temp.resolve("weekly-archive"),
                null,
                null,
                null,
                temp.resolve("weekly")
        ));

        assertThat(scanner.scan())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.fileType()).isEqualTo(ImportFileType.WEEKLY_INVENTORY);
                    assertThat(candidate.reportDate()).isEqualTo(java.time.LocalDate.of(2025, 4, 28));
                    assertThat(candidate.snapshotAt())
                            .isEqualTo(java.time.LocalDate.of(2025, 4, 28).atTime(12, 0));
                    assertThat(candidate.relativePath())
                            .isEqualTo(Path.of("weekly-inventory", "2025年", "沃尔玛PNK周报 2025.04.28 .xlsx"));
                });
    }

    private static SourceFileCandidate report(List<SourceFileCandidate> candidates, String fileName) {
        return candidates.stream()
                .filter(candidate -> candidate.path().getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow();
    }
}
