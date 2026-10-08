package com.ai.walmartads.importing.parser;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.application.SourceFileCandidate;
import com.ai.walmartads.importing.application.SourceScanner;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ActualSourceParserSmokeTest {

    private static final Path DATA_ROOT = Path.of("D:/PNK/17DATA");

    @Test
    void scansAndParsesOneRealFileOfEverySupportedType() throws Exception {
        assumeTrue(Files.isDirectory(DATA_ROOT), "本机真实报表目录不存在，跳过集成冒烟测试");

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                DATA_ROOT,
                DATA_ROOT.resolve("archive"),
                null,
                null,
                null
        ));
        List<SourceFileCandidate> candidates = scanner.scan();
        Map<ImportFileType, SourceFileCandidate> sampleByType = new EnumMap<>(ImportFileType.class);
        candidates.forEach(candidate -> sampleByType.putIfAbsent(candidate.fileType(), candidate));

        // date/ 日目录整目录已退役：ORDER（PO_Data 每日订单明细）、INVENTORY（inventory.xlsx 库存报表）
        // 与广告 CSV / 活动快照的扫描入口均已停用（领星 MCP 接管），正常扫描不会产出这几类候选。
        Set<ImportFileType> expectedTypes = EnumSet.of(ImportFileType.PRODUCT);
        assertThat(sampleByType.keySet()).containsAll(expectedTypes);

        JsonPayloadWriter json = new JsonPayloadWriter(new ObjectMapper());
        ProductWorkbookParser productParser = new ProductWorkbookParser(json);
        WalmartListingParser walmartListingParser = new WalmartListingParser();
        DemandForecastParser demandForecastParser = new DemandForecastParser(json);
        DomesticInventoryWorkbookParser domesticInventoryParser = new DomesticInventoryWorkbookParser(json);
        ProductionOrderParser productionOrderParser = new ProductionOrderParser(json);
        ShipmentWorkbookParser shipmentParser = new ShipmentWorkbookParser(json);
        ShipmentLogisticsWorkbookParser shipmentLogisticsParser = new ShipmentLogisticsWorkbookParser(json);

        for (SourceFileCandidate candidate : sampleByType.values()) {
            if (candidate.fileType() == ImportFileType.RETURN_ORDER) {
                continue;
            }
            ImportFile importFile = importFile(candidate);
            ParsedRows<?> parsed = switch (candidate.fileType()) {
                case ORDER -> throw new IllegalStateException("PO_Data 扫描入口已停用（领星 MCP 接管）");
                case INVENTORY -> throw new IllegalStateException("inventory.xlsx 库存报表扫描入口已停用（领星 MCP 接管）");
                case AD_REPORT, AD_AUTO, AD_MANUAL -> throw new IllegalStateException("广告 CSV 解析器已下线（领星 MCP 接管）");
                case PRODUCT -> productParser.parse(candidate.path(), importFile);
                case WALMART_LISTING -> walmartListingParser.parse(candidate.path(), importFile);
                case DEMAND_FORECAST -> demandForecastParser.parse(candidate.path(), importFile);
                case DOMESTIC_INVENTORY -> domesticInventoryParser.parse(candidate.path(), importFile);
                case PRODUCTION_ORDER -> productionOrderParser.parse(candidate.path(), importFile);
                case SHIPMENT -> shipmentParser.parse(candidate.path(), importFile);
                case SHIPMENT_LOGISTICS -> shipmentLogisticsParser.parse(candidate.path(), importFile);
                case HISTORY -> throw new IllegalStateException("Two-argument test configuration does not scan history");
                case SKU_MAPPING -> throw new IllegalStateException("Test configuration does not scan SKU mappings");
                case RETURN_ORDER -> throw new IllegalStateException("Dedicated return parser test covers return workbooks");
                case CAMPAIGN_SNAPSHOT -> throw new IllegalStateException("广告活动快照扫描入口已停用（领星 MCP 接管）");
                case WEEKLY_INVENTORY -> throw new IllegalStateException("沃尔玛PNK周报扫描链路已删除（2026-09-23）");
                // 领星订单由 MCP 同步直接写库，不存在可解析的样本文件，故永不走这条分支。
                case LINGXING_ORDER -> throw new IllegalStateException("LingXing order rows are synced via MCP, not parsed from files");
            };
            assertThat(parsed.sourceRowCount())
                    .as("源文件应有数据行：%s", candidate.path())
                    .isPositive();
            assertThat(parsed.rows())
                    .as("报表应解析出有效记录：%s", candidate.path())
                    .isNotEmpty();
        }
    }

    private static ImportFile importFile(SourceFileCandidate candidate) throws Exception {
        return new ImportFile(
                new ImportBatch(DATA_ROOT.toString()),
                candidate.fileType(),
                candidate.reportKind(),
                candidate.path().getFileName().toString(),
                candidate.path().toString(),
                "0".repeat(64),
                Files.size(candidate.path()),
                ImportValues.fileTime(Files.getLastModifiedTime(candidate.path()).toInstant()),
                candidate.reportDate(),
                candidate.snapshotAt()
        );
    }
}
