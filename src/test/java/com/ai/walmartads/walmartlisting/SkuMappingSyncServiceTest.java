package com.ai.walmartads.walmartlisting;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.SkuMapping;
import com.ai.walmartads.importing.parser.JsonPayloadWriter;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.importing.repository.OrderRecordRepository;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化领星在线商品 → {@code wm_ads_sku_mapping} 的写入契约：
 * 款号取 {@code local_name}（缺失才回落 SKU 前缀）、缺 SKU/Item ID 的行跳过、
 * 复用同一条 import_file 行、指纹一致时整段跳过写库。
 */
class SkuMappingSyncServiceTest {

    private ImportFileRepository fileRepository;
    private ImportBatchRepository batchRepository;
    private SkuMappingRepository mappingRepository;
    private OrderRecordRepository orderRepository;
    private SkuMappingSyncService service;

    @BeforeEach
    void setUp() {
        fileRepository = mock(ImportFileRepository.class);
        batchRepository = mock(ImportBatchRepository.class);
        mappingRepository = mock(SkuMappingRepository.class);
        orderRepository = mock(OrderRecordRepository.class);
        service = new SkuMappingSyncService(
                fileRepository,
                batchRepository,
                mappingRepository,
                orderRepository,
                new JsonPayloadWriter(new ObjectMapper())
        );
        when(batchRepository.save(any(ImportBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fileRepository.save(any(ImportFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void writesNormalizedRowsAndSkipsRowsWithoutSkuOrItemId() {
        when(fileRepository.findFirstByFileTypeAndActiveTrueOrderByIdDesc(ImportFileType.SKU_MAPPING))
                .thenReturn(Optional.empty());

        SkuMappingSyncService.RewriteSummary summary = service.rewrite(List.of(
                listing("PNK366066-C-Bleach White-XL-WFS", "PNK366066", "17931822087", "0078904478890", "789044788908", "Title A"),
                // 领星 local_name 缺失 → 回落 SKU 首个连字符之前的前缀
                listing("PNK366087-FP-Pink Star-1X-WFS", null, "20636160554", "00789044873640", "789044873640", "Title B"),
                // 缺 item_id → 丢弃
                listing("PNK999999-X", "PNK999999", null, "0078904478891", "789044788909", "no item id"),
                // 缺 msku → 丢弃
                listing(null, "PNK999998", "17931822088", "0078904478892", "789044788910", "no sku")
        ));

        assertThat(summary.writtenRows()).isEqualTo(2);
        assertThat(summary.skipped()).isFalse();

        ArgumentCaptor<List<SkuMapping>> captor = ArgumentCaptor.forClass(List.class);
        verify(mappingRepository).saveAllAndFlush(captor.capture());
        List<SkuMapping> rows = captor.getValue();
        assertThat(rows).hasSize(2);

        assertThat(rows).filteredOn(row -> "PNK366066-C-Bleach White-XL-WFS".equals(row.getSku()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getParentSku()).isEqualTo("PNK366066");
                    assertThat(row.getItemId()).isEqualTo("17931822087");
                    assertThat(row.getUpc()).isEqualTo("789044788908");
                    assertThat(row.getItemName()).isEqualTo("Title A");
                });

        assertThat(rows).filteredOn(row -> "PNK366087-FP-Pink Star-1X-WFS".equals(row.getSku()))
                .singleElement()
                .extracting(SkuMapping::getParentSku)
                .isEqualTo("PNK366087");

        verify(mappingRepository).deleteAllRows();
        verify(orderRepository).backfillUpcFromCurrentMappings();
    }

    @Test
    void derivesUpcFromPaddedGtinAndKeepsEmptyStringsWhenBarcodeMissing() {
        when(fileRepository.findFirstByFileTypeAndActiveTrueOrderByIdDesc(ImportFileType.SKU_MAPPING))
                .thenReturn(Optional.empty());

        service.rewrite(List.of(
                // upc 缺失但 gtin 是 00 前缀的 14 位 → 取后 12 位
                listing("PNK1-A", "PNK1", "111", "00789044873640", null, null),
                // 两者都缺 → 写空串（表列 NOT NULL），不因缺条码丢弃映射
                listing("PNK2-A", "PNK2", "222", null, null, null)
        ));

        ArgumentCaptor<List<SkuMapping>> captor = ArgumentCaptor.forClass(List.class);
        verify(mappingRepository).saveAllAndFlush(captor.capture());
        List<SkuMapping> rows = captor.getValue();

        assertThat(rows).filteredOn(row -> "PNK1-A".equals(row.getSku()))
                .singleElement()
                .extracting(SkuMapping::getUpc)
                .isEqualTo("789044873640");
        assertThat(rows).filteredOn(row -> "PNK2-A".equals(row.getSku()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getUpc()).isEmpty();
                    assertThat(row.getGtin()).isEmpty();
                });
    }

    @Test
    void skipsRewriteWhenDatasetFingerprintUnchanged() {
        when(fileRepository.findFirstByFileTypeAndActiveTrueOrderByIdDesc(ImportFileType.SKU_MAPPING))
                .thenReturn(Optional.empty());
        List<WalmartListingDto> listings = List.of(
                listing("PNK366066-C-Bleach White-XL-WFS", "PNK366066", "17931822087", "0078904478890", "789044788908", "Title A"));

        service.rewrite(listings);
        ArgumentCaptor<ImportFile> fileCaptor = ArgumentCaptor.forClass(ImportFile.class);
        verify(fileRepository, atLeastOnce()).save(fileCaptor.capture());
        ImportFile created = fileCaptor.getValue();

        reset(mappingRepository, orderRepository, fileRepository, batchRepository);
        when(fileRepository.findFirstByFileTypeAndActiveTrueOrderByIdDesc(ImportFileType.SKU_MAPPING))
                .thenReturn(Optional.of(created));

        SkuMappingSyncService.RewriteSummary second = service.rewrite(listings);

        assertThat(second.skipped()).isTrue();
        assertThat(second.writtenRows()).isZero();
        verify(mappingRepository, never()).deleteAllRows();
        verify(mappingRepository, never()).saveAllAndFlush(any());
        // 即便映射没变，订单 UPC 回填仍要跑（每天都有新订单进来）
        verify(orderRepository).backfillUpcFromCurrentMappings();
        // 复用同一行：不新建 batch / file
        verify(batchRepository, never()).save(any(ImportBatch.class));
        verify(fileRepository, never()).save(any(ImportFile.class));
    }

    @Test
    void keepsExistingMappingWhenListingsProduceNoUsableRow() {
        int written = service.rewrite(List.of(listing(null, null, null, null, null, null))).writtenRows();

        assertThat(written).isZero();
        verify(mappingRepository, never()).deleteAllRows();
        verify(mappingRepository, never()).saveAllAndFlush(any());
    }

    private static WalmartListingDto listing(
            String sku,
            String localName,
            String itemId,
            String gtin,
            String upc,
            String title
    ) {
        WalmartListingDto dto = new WalmartListingDto();
        dto.sku = sku;
        dto.parentSku = localName;
        dto.itemId = itemId;
        dto.gtin = gtin;
        dto.upc = upc;
        dto.title = title;
        return dto;
    }
}
