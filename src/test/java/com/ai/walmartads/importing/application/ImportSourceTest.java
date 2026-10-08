package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportFileType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ImportSourceTest {

    @Test
    void doesNotExposeRetiredAdvertisingFileSources() {
        assertThat(ImportSource.values())
                .extracting(Enum::name)
                .doesNotContain("ITEM_PERFORMANCE", "ITEM_KEYWORD_PERFORMANCE", "KEYWORD_PERFORMANCE", "CAMPAIGN_SNAPSHOT");
    }

    @Test
    void doesNotExposeRetiredFileSources() {
        // HISTORY / DEMAND_FORECAST / ORDER 于 2026-09-28 退出文件扫描链路；
        // SKU_MAPPING 同日换源为领星 MCP 在线商品（不再需要文件导入）。四者都不再是可选导入来源。
        assertThat(ImportSource.values())
                .extracting(Enum::name)
                .doesNotContain("HISTORY", "DEMAND_FORECAST", "ORDER", "SKU_MAPPING");
    }

    @Test
    void mapsEverySourceToItsOwnFileTypeAndMatchesItselfOnly() {
        for (ImportSource source : ImportSource.values()) {
            assertThat(source.fileType()).isNotNull();
            assertThat(source.matches(candidate(source.fileType(), null))).isTrue();
            for (ImportFileType other : ImportFileType.values()) {
                if (other == source.fileType()) {
                    continue;
                }
                assertThat(source.matches(candidate(other, null)))
                        .as("%s 不该匹配 %s", source, other)
                        .isFalse();
            }
        }
    }

    @Test
    void flagsOnlyFullReplaceSourcesAsClearingOnEmptySourceDirectory() {
        // 整类型替换口径：源目录为空时须把已有数据整体置空。口径单一真源在 ImportFileType#fullReplace。
        assertThat(ImportSource.values())
                .filteredOn(ImportSource::fullReplace)
                .containsExactlyInAnyOrder(
                        ImportSource.PRODUCTION_ORDER,
                        ImportSource.SHIPMENT,
                        ImportSource.SHIPMENT_LOGISTICS
                );
        // 快照序列 / 按 SKU / 按报表月三类不得纳入，否则会毁历史或误伤产品库。
        assertThat(ImportSource.DOMESTIC_INVENTORY.fullReplace()).isFalse();
        assertThat(ImportSource.PRODUCT.fullReplace()).isFalse();
        assertThat(ImportSource.RETURN_ORDER.fullReplace()).isFalse();
    }

    @Test
    void mapsEveryNonAdvertisingBusinessSourceToItsOwnOption() {
        assertThat(ImportSource.PRODUCT.matches(candidate(ImportFileType.PRODUCT, null))).isTrue();
        assertThat(ImportSource.RETURN_ORDER.matches(
                candidate(ImportFileType.RETURN_ORDER, null)
        )).isTrue();
        assertThat(ImportSource.DOMESTIC_INVENTORY.matches(
                candidate(ImportFileType.DOMESTIC_INVENTORY, null)
        )).isTrue();
        assertThat(ImportSource.PRODUCT.matches(candidate(ImportFileType.ORDER, null))).isFalse();
        // SKU_MAPPING 已退出导入范围：即便扫描器仍产出该类型候选，也不该有来源能匹配它。
        assertThat(ImportSource.values())
                .noneMatch(source -> source.matches(candidate(ImportFileType.SKU_MAPPING, null)));
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
