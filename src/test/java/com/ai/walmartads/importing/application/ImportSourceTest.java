package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportFileType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ImportSourceTest {

    @Test
    void distinguishesAllThreeAdvertisingReportSources() {
        SourceFileCandidate item = candidate(
                ImportFileType.AD_REPORT,
                AdReportKind.ITEM_PERFORMANCE
        );
        SourceFileCandidate automaticTerms = candidate(
                ImportFileType.AD_REPORT,
                AdReportKind.ITEM_KEYWORD_PERFORMANCE
        );
        SourceFileCandidate manualKeywords = candidate(
                ImportFileType.AD_REPORT,
                AdReportKind.KEYWORD_PERFORMANCE
        );

        assertThat(ImportSource.ITEM_PERFORMANCE.matches(item)).isTrue();
        assertThat(ImportSource.ITEM_KEYWORD_PERFORMANCE.matches(automaticTerms)).isTrue();
        assertThat(ImportSource.KEYWORD_PERFORMANCE.matches(manualKeywords)).isTrue();
        assertThat(ImportSource.KEYWORD_PERFORMANCE.matches(automaticTerms)).isFalse();
    }

    @Test
    void mapsEveryNonAdvertisingBusinessSourceToItsOwnOption() {
        assertThat(ImportSource.ORDER.matches(candidate(ImportFileType.ORDER, null))).isTrue();
        assertThat(ImportSource.INVENTORY.matches(candidate(ImportFileType.INVENTORY, null))).isTrue();
        assertThat(ImportSource.WEEKLY_INVENTORY.matches(
                candidate(ImportFileType.WEEKLY_INVENTORY, null)
        )).isTrue();
        assertThat(ImportSource.PRODUCT.matches(candidate(ImportFileType.PRODUCT, null))).isTrue();
        assertThat(ImportSource.RETURN_ORDER.matches(
                candidate(ImportFileType.RETURN_ORDER, null)
        )).isTrue();
        assertThat(ImportSource.SKU_MAPPING.matches(
                candidate(ImportFileType.SKU_MAPPING, null)
        )).isTrue();
        assertThat(ImportSource.HISTORY.matches(candidate(ImportFileType.HISTORY, null))).isTrue();
        assertThat(ImportSource.PRODUCT.matches(candidate(ImportFileType.ORDER, null))).isFalse();
    }

    private static SourceFileCandidate candidate(
            ImportFileType type,
            AdReportKind reportKind
    ) {
        return new SourceFileCandidate(
                Path.of(type.name() + ".xlsx"),
                Path.of(type.name() + ".xlsx"),
                type,
                reportKind,
                LocalDate.of(2026, 7, 22),
                null
        );
    }
}
