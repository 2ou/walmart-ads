package com.ai.walmartads.pricemonitor;

import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.pricemonitor.domain.PriceBaseline;
import com.ai.walmartads.pricemonitor.domain.PriceChange;
import com.ai.walmartads.pricemonitor.repository.PriceBaselineRepository;
import com.ai.walmartads.pricemonitor.repository.PriceChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 价格监控核心逻辑单测：首见基线、阈值判定（绿/红）、累计偏离、数据污染去重、确认。
 *
 * <p>阈值等配置用字段默认值（单测不走 Spring，@Value 不注入），与生产 yml 默认值一致。
 */
@ExtendWith(MockitoExtension.class)
class PriceMonitorServiceTest {

    @Mock
    private ProductRecordRepository productRecordRepository;

    @Mock
    private PriceBaselineRepository baselineRepository;

    @Mock
    private PriceChangeRepository changeRepository;

    private PriceMonitorService service;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);

    @BeforeEach
    void setUp() {
        service = new PriceMonitorService(productRecordRepository, baselineRepository, changeRepository);
    }

    private ProductRecord rec(String sku, String parent, BigDecimal price, LocalDateTime updatedAt) {
        ProductRecord r = new ProductRecord(
                null, updatedAt, sku, parent, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, "{}");
        r.setPrice(price);
        return r;
    }

    @Test
    void firstSightCreatesBaselineAndRecordsNoChange() {
        when(productRecordRepository.findAll()).thenReturn(List.of(rec("SKU1", "PNK001", new BigDecimal("14.00"), now)));
        when(baselineRepository.findBySku("SKU1")).thenReturn(Optional.empty());

        int changes = service.monitorOnce();

        assertThat(changes).isEqualTo(0);
        verify(baselineRepository, times(1)).save(any(PriceBaseline.class));
        verify(changeRepository, never()).save(any(PriceChange.class));
    }

    @Test
    void belowThresholdRecordsNoChangeButUpdatesLastPrice() {
        PriceBaseline baseline = new PriceBaseline("SKU1", "PNK001",
                new BigDecimal("14.00"), new BigDecimal("14.00"), now, now);
        when(productRecordRepository.findAll()).thenReturn(List.of(rec("SKU1", "PNK001", new BigDecimal("14.05"), now)));
        when(baselineRepository.findBySku("SKU1")).thenReturn(Optional.of(baseline));

        int changes = service.monitorOnce();

        assertThat(changes).isEqualTo(0);
        verify(changeRepository, never()).save(any(PriceChange.class));
        // 低于阈值仍更新 last_price，避免小数漂移被漏记（累计偏离仍相对基线）。
        ArgumentCaptor<PriceBaseline> cap = ArgumentCaptor.forClass(PriceBaseline.class);
        verify(baselineRepository).save(cap.capture());
        assertThat(cap.getValue().getLastPrice()).isEqualByComparingTo("14.05");
    }

    @Test
    void normalChangeWithin20PercentIsGreen() {
        PriceBaseline baseline = new PriceBaseline("SKU1", "PNK001",
                new BigDecimal("14.00"), new BigDecimal("14.00"), now, now);
        when(productRecordRepository.findAll()).thenReturn(List.of(rec("SKU1", "PNK001", new BigDecimal("14.50"), now)));
        when(baselineRepository.findBySku("SKU1")).thenReturn(Optional.of(baseline));

        int changes = service.monitorOnce();

        assertThat(changes).isEqualTo(1);
        ArgumentCaptor<PriceChange> cap = ArgumentCaptor.forClass(PriceChange.class);
        verify(changeRepository).save(cap.capture());
        PriceChange saved = cap.getValue();
        assertThat(saved.isAbnormal()).isFalse();
        assertThat(saved.getOldPrice()).isEqualByComparingTo("14.00");
        assertThat(saved.getNewPrice()).isEqualByComparingTo("14.50");
        // (14.50-14.00)/14.00*100 = 3.5714%
        assertThat(saved.getChangePct()).isEqualByComparingTo("3.5714");
        assertThat(saved.getCumulativePct()).isEqualByComparingTo("3.5714");
    }

    @Test
    void changeBeyond20PercentIsAbnormalRed() {
        PriceBaseline baseline = new PriceBaseline("SKU1", "PNK001",
                new BigDecimal("14.00"), new BigDecimal("14.00"), now, now);
        when(productRecordRepository.findAll()).thenReturn(List.of(rec("SKU1", "PNK001", new BigDecimal("18.00"), now)));
        when(baselineRepository.findBySku("SKU1")).thenReturn(Optional.of(baseline));

        int changes = service.monitorOnce();

        assertThat(changes).isEqualTo(1);
        ArgumentCaptor<PriceChange> cap = ArgumentCaptor.forClass(PriceChange.class);
        verify(changeRepository).save(cap.capture());
        assertThat(cap.getValue().isAbnormal()).isTrue();
        assertThat(cap.getValue().getChangePct()).isEqualByComparingTo("28.5714");
    }

    @Test
    void pollutionRowsDeduplicatedBySku() {
        // 同一 SKU 多行 current_version=1：一行无价、一行有价。只应按有价行建一条基线。
        ProductRecord noPrice = rec("SKU1", "PNK001", null, now.minusDays(1));
        ProductRecord withPrice = rec("SKU1", "PNK001", new BigDecimal("14.00"), now);
        when(productRecordRepository.findAll()).thenReturn(List.of(noPrice, withPrice));
        when(baselineRepository.findBySku("SKU1")).thenReturn(Optional.empty());

        int changes = service.monitorOnce();

        assertThat(changes).isEqualTo(0);
        verify(baselineRepository, times(1)).save(any(PriceBaseline.class));
    }

    @Test
    void acknowledgeMarksChangeResolved() {
        PriceChange change = new PriceChange("SKU1", "PNK001",
                new BigDecimal("14.00"), new BigDecimal("18.00"),
                new BigDecimal("28.5714"), new BigDecimal("28.5714"), now, false, true);
        when(changeRepository.findById(1L)).thenReturn(Optional.of(change));

        service.acknowledge(1L);

        assertThat(change.isAcknowledged()).isTrue();
        verify(changeRepository).save(change);
    }

    @Test
    void acknowledgeRejectsNormalPriceMovement() {
        PriceChange change = new PriceChange("SKU1", "PNK001",
                new BigDecimal("14.00"), new BigDecimal("14.50"),
                new BigDecimal("3.5714"), new BigDecimal("3.5714"), now, false, false);
        when(changeRepository.findById(1L)).thenReturn(Optional.of(change));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.acknowledge(1L));

        verify(changeRepository, never()).save(change);
    }

    @Test
    void acknowledgeMissingIdThrows() {
        when(changeRepository.findById(999L)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.acknowledge(999L));
    }

    @Test
    void acknowledgeStyleMarksEveryPendingAbnormalChangeForParentSku() {
        when(changeRepository.acknowledgeAbnormalByParentSku("PNK001")).thenReturn(7);

        int acknowledged = service.acknowledgeStyle(" pnk001 ");

        assertThat(acknowledged).isEqualTo(7);
        verify(changeRepository).acknowledgeAbnormalByParentSku("PNK001");
    }

    @Test
    void acknowledgeStyleRejectsBlankParentSku() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.acknowledgeStyle("  "));
        verify(changeRepository, never()).acknowledgeAbnormalByParentSku(any());
    }
}
