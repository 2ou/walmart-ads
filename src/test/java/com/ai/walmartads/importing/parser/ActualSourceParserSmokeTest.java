package com.ai.walmartads.importing.parser;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.application.SourceFileCandidate;
import com.ai.walmartads.importing.application.SourceScanner;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActualSourceParserSmokeTest {

    private static final Path DATA_ROOT = Path.of("D:/PNK/17DATA");

    @Test
    void scansAndParsesOneRealFileOfEverySupportedType() throws Exception {
        assumeTrue(Files.isDirectory(DATA_ROOT), "本机真实报表目录不存在，跳过集成冒烟测试");

        SourceScanner scanner = new SourceScanner(new WalmartAdsProperties(
                DATA_ROOT,
                DATA_ROOT.resolve("archive")
        ));
        List<SourceFileCandidate> candidates = scanner.scan();
        Map<ImportFileType, SourceFileCandidate> sampleByType = new EnumMap<>(ImportFileType.class);
        candidates.forEach(candidate -> sampleByType.putIfAbsent(candidate.fileType(), candidate));

        assertThat(sampleByType.keySet()).contains(
                ImportFileType.ORDER,
                ImportFileType.AD_AUTO,
                ImportFileType.AD_MANUAL,
                ImportFileType.INVENTORY,
                ImportFileType.PRODUCT
        );

        JsonPayloadWriter json = new JsonPayloadWriter(new ObjectMapper());
        InventorySnapshotRepository inventoryRepository = mock(InventorySnapshotRepository.class);
        when(inventoryRepository.findLatestByItemId(anyString())).thenReturn(Optional.empty());
        AdCsvParser adParser = new AdCsvParser(json, new SkuResolver(inventoryRepository));
        OrderWorkbookParser orderParser = new OrderWorkbookParser(json);
        InventoryWorkbookParser inventoryParser = new InventoryWorkbookParser(json);
        ProductWorkbookParser productParser = new ProductWorkbookParser(json);

        for (SourceFileCandidate candidate : sampleByType.values()) {
            ImportFile importFile = importFile(candidate);
            ParsedRows<?> parsed = switch (candidate.fileType()) {
                case ORDER -> orderParser.parse(candidate.path(), importFile);
                case AD_AUTO, AD_MANUAL -> adParser.parse(candidate.path(), importFile);
                case INVENTORY -> inventoryParser.parse(candidate.path(), importFile);
                case PRODUCT -> productParser.parse(candidate.path(), importFile);
            };
            assertThat(parsed.sourceRowCount())
                    .as("源文件应有数据行：%s", candidate.path())
                    .isPositive();
            if (candidate.fileType() != ImportFileType.ORDER) {
                assertThat(parsed.rows())
                        .as("报表应解析出有效记录：%s", candidate.path())
                        .isNotEmpty();
            }
        }
    }

    private static ImportFile importFile(SourceFileCandidate candidate) throws Exception {
        return new ImportFile(
                new ImportBatch(DATA_ROOT.toString()),
                candidate.fileType(),
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
