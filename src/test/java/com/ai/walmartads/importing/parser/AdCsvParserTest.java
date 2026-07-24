package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdCsvParserTest {

    @TempDir
    Path temp;

    @Test
    void classifiesEveryRowFromCampaignType() throws Exception {
        Path csv = temp.resolve("ItemKeywordPerformance_test.csv");
        Files.writeString(csv, """
                Campaign Name,Campaign Type,Ad Spend,Total Attributed Sales,RoAS
                AUTO-CAMPAIGN,Sponsored Products (Auto),1.20,4.80,4
                MANUAL-CAMPAIGN,Sponsored Products (Manual),2.00,6.00,3
                """);
        ImportFile importFile = new ImportFile(
                new ImportBatch(temp.toString()),
                ImportFileType.AD_REPORT,
                AdReportKind.ITEM_KEYWORD_PERFORMANCE,
                csv.getFileName().toString(),
                csv.toString(),
                "0".repeat(64),
                Files.size(csv),
                LocalDateTime.now(),
                LocalDate.of(2026, 7, 22),
                null
        );
        AdCsvParser parser = new AdCsvParser(
                new JsonPayloadWriter(new ObjectMapper()),
                new SkuResolver(
                        mock(InventorySnapshotRepository.class),
                        mock(SkuMappingRepository.class)
                )
        );

        ParsedRows<AdPerformance> parsed = parser.parse(csv, importFile);

        assertThat(parsed.rows())
                .extracting(AdPerformance::getAdType)
                .containsExactly(ImportFileType.AD_AUTO, ImportFileType.AD_MANUAL);
    }
}
