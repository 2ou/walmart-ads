package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
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
    void ignoresRetiredDailyReportsUnderDateDirectory() throws Exception {
        Path day = Files.createDirectories(temp.resolve("date").resolve("2026-7-22"));
        Files.createFile(day.resolve("ItemPerformance_example.csv"));
        Files.createFile(day.resolve("ItemKeywordPerformance_example.csv"));
        Files.createFile(day.resolve("KeywordPerformance_example.csv"));
        // PO_Data（每日订单明细）已由领星 MCP 接管，扫描必须完全忽略，避免与领星口径重复入账。
        Files.createFile(day.resolve("PO_Data_example.xlsx"));
        // inventory.xlsx（Walmart 后台库存健康报表）同理由领星 MCP 在线商品库存接管。
        Files.createFile(day.resolve("inventory.xlsx"));

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                temp, temp.resolve("archive"), null, null, null
        ));
        List<SourceFileCandidate> candidates = scanner.scan();

        // date/ 日目录整目录已退役：订单、库存、广告三类日报都不再进扫描结果。
        assertThat(candidates).isEmpty();
    }

    @Test
    void ignoresLegacyRootDateDirectory() throws Exception {
        Path day = Files.createDirectories(temp.resolve("2026-7-21"));
        Files.createFile(day.resolve("inventory.xlsx"));

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                temp, temp.resolve("archive"), null, null, null
        ));

        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void ignoresRetiredHistoricalWorkbookEvenWhenConfigured() throws Exception {
        // HISTORY 已于 2026-09-28 退出文件扫描链路：即使 data-root / historical-workbook 都配好，
        // 也不再产出候选（历史订单早已落库，ImportFileType.HISTORY 仅用于读回历史 import_file 行）。
        Path dataRoot = Files.createDirectories(temp.resolve("data"));
        Path workbook = Files.createFile(temp.resolve("PNK 每日销售数据统计.xlsx"));
        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot,
                temp.resolve("archive"),
                workbook,
                java.time.LocalDate.of(2026, 7, 21),
                null
        ));

        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void ignoresRetiredDemandForecastDirectory() throws Exception {
        // source/demand-forecast/ 已不存在，扫描链路一并摘除；残留目录不得再产出候选。
        Path dataRoot = Files.createDirectories(temp.resolve("demand-data"));
        Path demand = Files.createDirectories(dataRoot.resolve("source").resolve("demand-forecast"));
        Files.createFile(demand.resolve("demand.xlsx"));
        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot, temp.resolve("demand-archive"), null, null, null
        ));

        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void scansDomesticInventoryUnderSourceDirectory() throws Exception {
        Path dataRoot = Files.createDirectories(temp.resolve("domestic-data"));
        Path domestic = Files.createDirectories(dataRoot.resolve("source").resolve("国内仓库存"));
        Files.createFile(domestic.resolve("国内仓库存-20260901.xlsx"));
        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot, temp.resolve("domestic-archive"), null, null, null
        ));

        assertThat(scanner.scan())
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.fileType())
                        .isEqualTo(ImportFileType.DOMESTIC_INVENTORY));
    }

    @Test
    void ignoresRetiredSkuMappingWorkbookEvenWhenConfigured() throws Exception {
        // SKU 映射已于 2026-09-28 换源为领星 MCP 在线商品：即使 data-root / sku-mapping-workbook
        // 都配好，也不再产出候选（ImportFileType.SKU_MAPPING 仅用于读回历史 import_file 行）。
        Path dataRoot = Files.createDirectories(temp.resolve("mapping-data"));
        Path day = Files.createDirectories(dataRoot.resolve("date").resolve("2026-7-22"));
        Files.createFile(day.resolve("ItemPerformance_example.csv"));
        Files.createFile(day.resolve("inventory.xlsx"));
        Path workbook = Files.createFile(temp.resolve("WFS-upload-template.xlsx"));
        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot,
                temp.resolve("mapping-archive"),
                null,
                null,
                workbook
        ));

        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void scansMonthlyReturnOrdersUsingMonthFromFileName() throws Exception {
        Path dataRoot = Files.createDirectories(temp.resolve("return-data"));
        Path returns = Files.createDirectories(dataRoot.resolve("source").resolve("Return_Orders"));
        Files.createFile(returns.resolve("Customer returns-202606.xlsx"));
        Files.createFile(returns.resolve("notes.xlsx"));

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot, temp.resolve("return-archive"), null, null, null
        ));

        assertThat(scanner.scan())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.fileType()).isEqualTo(ImportFileType.RETURN_ORDER);
                    assertThat(candidate.reportDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 1));
                    assertThat(candidate.relativePath())
                            .isEqualTo(Path.of("source", "Return_Orders", "Customer returns-202606.xlsx"));
                });
    }

    @Test
    void ignoresCampaignSnapshotJsonAfterLingXingSync() throws Exception {
        Path dataRoot = Files.createDirectories(temp.resolve("snapshot-data"));
        Path snapshot = Files.createDirectories(dataRoot.resolve("source").resolve("snapshot"))
                .resolve("snapshot.json");
        Files.writeString(snapshot, "{\"campaigns\":[],\"adItem\":[]}");
        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                dataRoot, temp.resolve("snapshot-archive"), null, null, null
        ));

        // 广告活动快照已改由领星 MCP 同步（wm_ads_lx_campaign_snapshot），文件扫描入口软禁用。
        assertThat(scanner.scan())
                .noneMatch(candidate -> candidate.fileType() == ImportFileType.CAMPAIGN_SNAPSHOT);
    }
}
