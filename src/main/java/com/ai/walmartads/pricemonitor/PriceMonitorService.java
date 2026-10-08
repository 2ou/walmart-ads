package com.ai.walmartads.pricemonitor;

import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.pricemonitor.domain.PriceBaseline;
import com.ai.walmartads.pricemonitor.domain.PriceChange;
import com.ai.walmartads.pricemonitor.repository.PriceBaselineRepository;
import com.ai.walmartads.pricemonitor.repository.PriceChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 价格监控：复用 {@code wm_ads_product_record.price}（沃尔玛在线在售价格，每小时由领星 MCP 回写），
 * 周期性快照并比对，检测价格变动。
 *
 * <h2>为什么不用每次去领星拉</h2>
 * 系统已有 {@code WalmartListingSyncService} 每小时把沃尔玛在售价写回 {@code product_record.price}，
 * 故监控只需读这份已刷新的价格 + 做环比差值，零新增领星调用、零额外 API 成本。
 *
 * <h2>数据污染规避</h2>
 * {@code wm_ads_product_record} 存在 {@code current_version=1} 多行污染（历史导入未把旧行置 false），
 * 同款多个 SKU 行里可能只有部分带价格。故按 sku 去重、优先取非空价格且 {@code sourceUpdatedAt} 最新的一行。
 *
 * <h2>变动 / 异常口径</h2>
 * 每次读刷新后的 price，与基线表的 {@code last_price} 比：环比幅度 {@code |(新-旧)/旧|} ≥ change-threshold-percent
 * （默认 1%）才落一条变动记录；其中 {@code |change_pct| > abnormal-percent}（默认 20%）标记为异常（红），
 * 否则正常（绿）。累计偏离 {@code cumulative_pct} 始终相对首次基线价计算，仅作信息展示。
 */
@Service
public class PriceMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PriceMonitorService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_CHANGES_PER_RUN_LOG = 200;

    private final ProductRecordRepository productRecordRepository;
    private final PriceBaselineRepository baselineRepository;
    private final PriceChangeRepository changeRepository;

    @Value("${walmart.ads.price-monitor.enabled:true}")
    private boolean enabled = true;

    @Value("${walmart.ads.price-monitor.change-threshold-percent:1.0}")
    private BigDecimal changeThresholdPercent = new BigDecimal("1.0");

    @Value("${walmart.ads.price-monitor.abnormal-percent:20.0}")
    private BigDecimal abnormalPercent = new BigDecimal("20.0");

    @Value("${walmart.ads.price-monitor.retention-days:180}")
    private int retentionDays = 180;

    public PriceMonitorService(
            ProductRecordRepository productRecordRepository,
            PriceBaselineRepository baselineRepository,
            PriceChangeRepository changeRepository
    ) {
        this.productRecordRepository = productRecordRepository;
        this.baselineRepository = baselineRepository;
        this.changeRepository = changeRepository;
    }

    /** 每小时定时比对（cron 可配，默认整点）。 */
    @Transactional
    public void scheduledMonitor() {
        monitorOnce();
    }

    /**
     * 手动触发一次比对（与定时同口径）。返回本次检测到的变动条数。
     * 控台 / 前端「立即扫描」按钮可调用，便于不等待整点验证效果。
     */
    @Transactional
    public int monitorOnce() {
        if (!enabled) {
            log.info("价格监控已禁用（price-monitor.enabled=false），跳过本次扫描");
            return 0;
        }
        Map<String, SkuPrice> current = loadCurrentPrices();
        LocalDateTime now = LocalDateTime.now();
        int changes = 0;
        int abnormalChanges = 0;
        for (Map.Entry<String, SkuPrice> entry : current.entrySet()) {
            String sku = entry.getKey();
            SkuPrice cp = entry.getValue();
            PriceBaseline baseline = baselineRepository.findBySku(sku).orElse(null);
            if (baseline == null) {
                // 首见：写入基线，不记录变动。
                baselineRepository.save(new PriceBaseline(
                        sku, cp.parentSku(), cp.price(), cp.price(), now, now));
                continue;
            }
            baseline.setParentSku(cp.parentSku());
            baseline.setLastSeenAt(now);
            BigDecimal last = baseline.getLastPrice();
            if (last != null && last.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deltaPct = cp.price().subtract(last)
                        .divide(last, 8, RoundingMode.HALF_UP)
                        .multiply(HUNDRED)
                        .setScale(4, RoundingMode.HALF_UP);
                BigDecimal base = baseline.getBaselinePrice();
                BigDecimal cumulativePct = (base != null && base.compareTo(BigDecimal.ZERO) > 0)
                        ? cp.price().subtract(base)
                            .divide(base, 8, RoundingMode.HALF_UP)
                            .multiply(HUNDRED)
                            .setScale(4, RoundingMode.HALF_UP)
                        : deltaPct;
                if (deltaPct.abs().compareTo(changeThresholdPercent) >= 0) {
                    boolean abnormal = deltaPct.abs().compareTo(abnormalPercent) > 0;
                    changeRepository.save(new PriceChange(
                            sku, cp.parentSku(), last, cp.price(),
                            deltaPct, cumulativePct, now, false, abnormal));
                    changes++;
                    if (abnormal) {
                        abnormalChanges++;
                    }
                }
            }
            baseline.setLastPrice(cp.price());
            baselineRepository.save(baseline);
        }
        cleanupRetention(now);
        if (changes > 0) {
            log.info("价格监控扫描完成：{} 个 SKU 首见或已监控，本次检测到 {} 条变动（其中异常 {} 条）",
                    current.size(), changes, abnormalChanges);
        }
        return changes;
    }

    /** 标记一条变动为已处理（仅红色异常需手动调用；绿色不进确认流程）。 */
    @Transactional
    public void acknowledge(Long id) {
        PriceChange change = changeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("价格变动记录不存在: " + id));
        if (!change.isAbnormal()) {
            throw new IllegalArgumentException("正常价格变动无需标记处理");
        }
        if (change.isAcknowledged()) {
            return;
        }
        change.setAcknowledged(true);
        changeRepository.save(change);
        log.info("价格变动 {} 已标记为已处理", id);
    }

    /** 批量标记一个款号下全部未确认异常变动，返回实际处理条数。 */
    @Transactional
    public int acknowledgeStyle(String parentSku) {
        String normalized = parentSku == null ? "" : parentSku.trim().toUpperCase();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("款号不能为空");
        }
        int acknowledged = changeRepository.acknowledgeAbnormalByParentSku(normalized);
        log.info("款号 {} 的 {} 条异常价格变动已批量标记为已处理", normalized, acknowledged);
        return acknowledged;
    }

    /** 保留期清理：普通变动和已处理异常按期清理；未处理异常保留到人工处理。 */
    @Transactional
    void cleanupRetention(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(retentionDays);
        int removed = changeRepository.deleteBefore(cutoff);
        if (removed > 0) {
            log.debug("价格变动记录清理：删除 {} 条早于 {} 的记录", removed, cutoff);
        }
    }

    /**
     * 读取当前有效款的价格，按 sku 去重（规避 current_version=1 多行污染）：
     * 同一 sku 取「非空价格且 sourceUpdatedAt 最新」的一行。
     */
    private Map<String, SkuPrice> loadCurrentPrices() {
        Map<String, SkuPrice> map = new HashMap<>();
        for (ProductRecord record : productRecordRepository.findAll()) {
            if (!record.isCurrentVersion()) {
                continue;
            }
            String sku = record.getSku();
            BigDecimal price = record.getPrice();
            if (sku == null || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            SkuPrice current = map.get(sku);
            if (current == null || isNewer(record, current)) {
                map.put(sku, new SkuPrice(record.getParentSku(), price, record));
            }
        }
        log.debug("loadCurrentPrices: 去重后 {} 个有效 SKU 带价格", map.size());
        return map;
    }

    /** 同 sku 多行时，取 sourceUpdatedAt 更新的一行。 */
    private boolean isNewer(ProductRecord candidate, SkuPrice existing) {
        LocalDateTime c = candidate.getSourceUpdatedAt();
        LocalDateTime e = existing.record().getSourceUpdatedAt();
        if (c != null && e != null) {
            return c.isAfter(e);
        }
        return c != null;
    }

    /** 单个 SKU 的去重结果。 */
    private record SkuPrice(String parentSku, BigDecimal price, ProductRecord record) {
    }
}
