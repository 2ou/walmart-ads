package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportFileType;
import org.junit.jupiter.api.Test;

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
        // HISTORY / DEMAND_FORECAST / ORDER 于 2026-09-28 退出文件导入；
        // SKU_MAPPING 同日换源为领星 MCP 在线商品（不再需要文件导入）。四者都不再是可选导入来源。
        assertThat(ImportSource.values())
                .extracting(Enum::name)
                .doesNotContain("HISTORY", "DEMAND_FORECAST", "ORDER", "SKU_MAPPING");
    }

    @Test
    void mapsEveryUploadSourceToItsFileType() {
        for (ImportSource source : ImportSource.values()) {
            assertThat(source.fileType()).isNotNull();
        }
        assertThat(ImportSource.PRODUCT.fileType()).isEqualTo(ImportFileType.PRODUCT);
        assertThat(ImportSource.RETURN_ORDER.fileType()).isEqualTo(ImportFileType.RETURN_ORDER);
        assertThat(ImportSource.PRODUCTION_ORDER.fileType()).isEqualTo(ImportFileType.PRODUCTION_ORDER);
        assertThat(ImportSource.SHIPMENT.fileType()).isEqualTo(ImportFileType.SHIPMENT);
        assertThat(ImportSource.SHIPMENT_LOGISTICS.fileType()).isEqualTo(ImportFileType.SHIPMENT_LOGISTICS);
        assertThat(ImportSource.DOMESTIC_INVENTORY.fileType()).isEqualTo(ImportFileType.DOMESTIC_INVENTORY);
    }
}
