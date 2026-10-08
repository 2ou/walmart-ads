package com.ai.walmartads.pricemonitor;

import com.ai.walmartads.pricemonitor.domain.PriceBaseline;
import com.ai.walmartads.pricemonitor.domain.PriceChange;
import com.ai.walmartads.pricemonitor.repository.PriceBaselineRepository;
import com.ai.walmartads.pricemonitor.repository.PriceChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 价格监控查询单测：角标汇总（款数 / 未确认红色数）与按款分组（红色优先、款式量降序）。
 */
@ExtendWith(MockitoExtension.class)
class PriceMonitorQueryServiceTest {

    @Mock
    private PriceChangeRepository changeRepository;

    @Mock
    private PriceBaselineRepository baselineRepository;

    private PriceMonitorQueryService service;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);

    @BeforeEach
    void setUp() {
        service = new PriceMonitorQueryService(changeRepository, baselineRepository);
    }

    private PriceChange change(Long id, String parent, String sku, BigDecimal oldP, BigDecimal newP,
                               BigDecimal pct, boolean abnormal, boolean ack) {
        return new PriceChange(sku, parent, oldP, newP, pct, pct, now, ack, abnormal);
    }

    @Test
    void summaryCountsStylesAndAbnormal() {
        PriceChange red1 = change(1L, "PNK001", "SKU1", new BigDecimal("14.00"), new BigDecimal("18.00"),
                new BigDecimal("28.57"), true, false);
        PriceChange red2 = change(2L, "PNK001", "SKU2", new BigDecimal("14.00"), new BigDecimal("18.00"),
                new BigDecimal("28.57"), true, false);
        PriceChange green = change(3L, "PNK002", "SKU3", new BigDecimal("14.00"), new BigDecimal("14.50"),
                new BigDecimal("3.57"), false, false);

        when(changeRepository.findByAcknowledgedFalseAndAbnormalTrueOrderByDetectedAtDesc())
                .thenReturn(List.of(red1, red2));
        when(changeRepository.findByAbnormalFalseAndDetectedAtAfterOrderByDetectedAtDesc(anyPast()))
                .thenReturn(List.of(green));

        Map<String, Object> summary = service.summary();

        assertThat(summary.get("styleCount")).isEqualTo(2);          // PNK001 + PNK002
        assertThat(summary.get("abnormalStyleCount")).isEqualTo(1);   // 仅 PNK001 含红
        assertThat(summary.get("abnormal")).isEqualTo(true);
        assertThat(summary.get("changeCount")).isEqualTo(3);
        assertThat(summary.get("pendingCount")).isEqualTo(2);
        assertThat(summary.get("abnormalPercent")).isEqualTo(new BigDecimal("20.0"));
    }

    @Test
    void acknowledgedRedDoesNotCount() {
        PriceChange ackedRed = change(1L, "PNK001", "SKU1", new BigDecimal("14.00"), new BigDecimal("18.00"),
                new BigDecimal("28.57"), true, true);
        when(changeRepository.findByAcknowledgedFalseAndAbnormalTrueOrderByDetectedAtDesc())
                .thenReturn(List.of());
        when(changeRepository.findByAbnormalFalseAndDetectedAtAfterOrderByDetectedAtDesc(anyPast()))
                .thenReturn(List.of());

        Map<String, Object> summary = service.summary();

        assertThat(summary.get("styleCount")).isEqualTo(0);
        assertThat(summary.get("abnormal")).isEqualTo(false);
    }

    @Test
    void changesGroupedByStyleWithAbnormalFirst() {
        PriceChange red = change(1L, "PNK001", "SKU1", new BigDecimal("14.00"), new BigDecimal("18.00"),
                new BigDecimal("28.57"), true, false);
        PriceChange greenA = change(2L, "PNK002", "SKU2", new BigDecimal("14.00"), new BigDecimal("14.50"),
                new BigDecimal("3.57"), false, false);
        PriceChange greenB = change(3L, "PNK002", "SKU3", new BigDecimal("14.50"), new BigDecimal("14.90"),
                new BigDecimal("2.76"), false, false);

        when(changeRepository.findByAcknowledgedFalseAndAbnormalTrueOrderByDetectedAtDesc())
                .thenReturn(List.of(red));
        when(changeRepository.findByAbnormalFalseAndDetectedAtAfterOrderByDetectedAtDesc(anyPast()))
                .thenReturn(List.of(greenA, greenB));

        Map<String, Object> changes = service.changes();
        List<Map<String, Object>> groups = (List<Map<String, Object>>) changes.get("groups");

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("parentSku")).isEqualTo("PNK001");   // 含异常优先
        assertThat(groups.get(0).get("hasAbnormal")).isEqualTo(true);
        assertThat(groups.get(0).get("changeCount")).isEqualTo(1);
        assertThat(groups.get(0).get("abnormalCount")).isEqualTo(1);
        assertThat(groups.get(0).get("skuCount")).isEqualTo(1);
        assertThat(groups.get(0).get("increaseCount")).isEqualTo(1);
        assertThat(groups.get(0).get("maxAbsChangePct")).isEqualTo(new BigDecimal("28.57"));
        assertThat(groups.get(1).get("parentSku")).isEqualTo("PNK002");
        assertThat(groups.get(1).get("hasAbnormal")).isEqualTo(false);
        assertThat(groups.get(1).get("changeCount")).isEqualTo(2);
        assertThat(groups.get(1).get("abnormalCount")).isEqualTo(0);
        assertThat(changes.get("totalChanges")).isEqualTo(3);
    }

    @Test
    void historyIncludesAcknowledgedAbnormalChanges() {
        PriceChange ackedRed = change(1L, "PNK001", "SKU1", new BigDecimal("14.00"), new BigDecimal("18.00"),
                new BigDecimal("28.57"), true, true);
        Page<PriceChange> page = new PageImpl<>(List.of(ackedRed));
        when(changeRepository.findAll(ArgumentMatchers.<Specification<PriceChange>>any(), any(Pageable.class)))
                .thenReturn(page);

        Map<String, Object> history = service.history("PNK001", null, null, 0, 20);

        assertThat(history.get("total")).isEqualTo(1L);
        List<Map<String, Object>> items = (List<Map<String, Object>>) history.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("acknowledged")).isEqualTo(true);   // 已确认的红色在历史中仍可见
        assertThat(items.get(0).get("abnormal")).isEqualTo(true);
    }

    @Test
    void historyClampsOversizedPageSize() {
        when(changeRepository.findAll(ArgumentMatchers.<Specification<PriceChange>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> history = service.history(null, null, 30, 0, 5000);

        assertThat(history.get("size")).isEqualTo(100);   // MAX_HISTORY_PAGE_SIZE 上限
        assertThat(history.get("total")).isEqualTo(0L);
    }

    @Test
    void trendBuildsBaselineChangeCurrentSequence() {
        PriceBaseline baseline = new PriceBaseline("SKU1", "PNK001",
                new BigDecimal("14.00"), new BigDecimal("18.00"),
                LocalDateTime.of(2026, 9, 1, 0, 0), now);
        PriceChange red = change(1L, "PNK001", "SKU1", new BigDecimal("14.00"), new BigDecimal("18.00"),
                new BigDecimal("28.57"), true, false);
        when(baselineRepository.findBySku("SKU1")).thenReturn(Optional.of(baseline));
        when(changeRepository.findBySkuOrderByDetectedAtAsc("SKU1")).thenReturn(List.of(red));

        Map<String, Object> trend = service.trend("SKU1");

        assertThat(trend.get("sku")).isEqualTo("SKU1");
        assertThat(trend.get("currentPrice")).isEqualTo(new BigDecimal("18.00"));
        List<Map<String, Object>> points = (List<Map<String, Object>>) trend.get("points");
        assertThat(points).hasSize(3);
        assertThat(points.get(0).get("kind")).isEqualTo("BASELINE");
        assertThat(points.get(1).get("kind")).isEqualTo("CHANGE");
        assertThat(points.get(2).get("kind")).isEqualTo("CURRENT");
    }

    @Test
    void trendRejectsBlankSku() {
        assertThatThrownBy(() -> service.trend("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU 不能为空");
    }

    private static java.time.LocalDateTime anyPast() {
        return org.mockito.ArgumentMatchers.any(LocalDateTime.class);
    }
}
