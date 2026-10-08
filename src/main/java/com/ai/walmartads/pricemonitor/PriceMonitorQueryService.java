package com.ai.walmartads.pricemonitor;

import com.ai.walmartads.pricemonitor.domain.PriceBaseline;
import com.ai.walmartads.pricemonitor.domain.PriceChange;
import com.ai.walmartads.pricemonitor.repository.PriceBaselineRepository;
import com.ai.walmartads.pricemonitor.repository.PriceChangeRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 价格监控查询：右上角角标数据 + 按款分组的变动列表 + 全量变动历史 + 单 SKU 价格走势。
 *
 * <p>活跃变动 = 未确认的红色异常（保留至确认） ∪ 窗口内的绿色正常变动（绿色不进确认流程）。
 * 角标数字 = 有活跃变动的款数；存在未确认红色时角标转红。
 *
 * <p>「历史」与「活跃」的差别：历史<b>不受展示窗口与确认状态限制</b>——已确认的红色异常、
 * 超出 30 天展示窗口的变动都能查到，仅受保留期（{@code retention-days}，默认 180 天）物理清理约束。
 */
@Service
@Transactional(readOnly = true)
public class PriceMonitorQueryService {

    private static final Logger log = LoggerFactory.getLogger(PriceMonitorQueryService.class);
    private static final int MAX_CHANGES_PER_STYLE = 20;

    /** 历史分页单页上限，避免一次拉全表。 */
    private static final int MAX_HISTORY_PAGE_SIZE = 100;

    private final PriceChangeRepository changeRepository;
    private final PriceBaselineRepository baselineRepository;

    @Value("${walmart.ads.price-monitor.display-window-days:30}")
    private int displayWindowDays = 30;

    /** 进入事件表的最小变动幅度，供前端解释当前口径。 */
    @Value("${walmart.ads.price-monitor.change-threshold-percent:1.0}")
    private BigDecimal changeThresholdPercent = new BigDecimal("1.0");

    /** 异常阈值，避免前端写死 20% 后与服务端配置漂移。 */
    @Value("${walmart.ads.price-monitor.abnormal-percent:20.0}")
    private BigDecimal abnormalPercent = new BigDecimal("20.0");

    /** 变动记录保留期（与 PriceMonitorService 的 cleanupRetention 同源配置），仅用于前端提示。 */
    @Value("${walmart.ads.price-monitor.retention-days:180}")
    private int retentionDays = 180;

    public PriceMonitorQueryService(PriceChangeRepository changeRepository,
                                    PriceBaselineRepository baselineRepository) {
        this.changeRepository = changeRepository;
        this.baselineRepository = baselineRepository;
    }

    /** 右上角角标数据。 */
    public Map<String, Object> summary() {
        LocalDateTime windowFrom = LocalDateTime.now().minusDays(displayWindowDays);
        List<PriceChange> red = changeRepository
                .findByAcknowledgedFalseAndAbnormalTrueOrderByDetectedAtDesc();
        List<PriceChange> green = changeRepository
                .findByAbnormalFalseAndDetectedAtAfterOrderByDetectedAtDesc(windowFrom);

        Set<String> styles = new LinkedHashSet<>();
        Set<String> abnormalStyles = new LinkedHashSet<>();
        red.forEach(c -> {
            styles.add(c.getParentSku());
            abnormalStyles.add(c.getParentSku());
        });
        green.forEach(c -> styles.add(c.getParentSku()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("styleCount", styles.size());
        summary.put("changeCount", red.size() + green.size());
        summary.put("pendingCount", red.size());
        summary.put("abnormalStyleCount", abnormalStyles.size());
        summary.put("abnormal", !abnormalStyles.isEmpty());
        summary.put("displayWindowDays", displayWindowDays);
        summary.put("retentionDays", retentionDays);
        summary.put("changeThresholdPercent", changeThresholdPercent);
        summary.put("abnormalPercent", abnormalPercent);
        return summary;
    }

    /** 按款分组的变动列表（红色异常优先、款式量降序）。 */
    public Map<String, Object> changes() {
        LocalDateTime windowFrom = LocalDateTime.now().minusDays(displayWindowDays);
        List<PriceChange> red = changeRepository
                .findByAcknowledgedFalseAndAbnormalTrueOrderByDetectedAtDesc();
        List<PriceChange> green = changeRepository
                .findByAbnormalFalseAndDetectedAtAfterOrderByDetectedAtDesc(windowFrom);

        Map<String, GroupAcc> groups = new LinkedHashMap<>();
        for (PriceChange c : red) {
            groups.computeIfAbsent(c.getParentSku(), key -> new GroupAcc(key)).add(c, true);
        }
        for (PriceChange c : green) {
            groups.computeIfAbsent(c.getParentSku(), key -> new GroupAcc(key)).add(c, false);
        }

        List<Map<String, Object>> out = new ArrayList<>(groups.size());
        for (GroupAcc acc : groups.values()) {
            out.add(acc.toView());
        }
        out.sort(Comparator.comparing((Map<String, Object> g) -> (boolean) g.get("hasAbnormal")).reversed()
                .thenComparing(
                        (Map<String, Object> g) -> (int) g.get("changeCount"),
                        Comparator.reverseOrder()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", out);
        result.put("totalChanges", red.size() + green.size());
        result.put("abnormalCount", red.size());
        result.put("styleCount", out.size());
        result.put("abnormalStyleCount", out.stream().filter(group -> (boolean) group.get("hasAbnormal")).count());
        result.put("displayWindowDays", displayWindowDays);
        result.put("retentionDays", retentionDays);
        result.put("changeThresholdPercent", changeThresholdPercent);
        result.put("abnormalPercent", abnormalPercent);
        result.put("latestDetectedAt", latestDetectedAt(red, green));
        return result;
    }

    /** 单款变动明细（抽屉下钻用，未确认红 + 窗口内绿）。 */
    public List<Map<String, Object>> styleChanges(String parentSku) {
        LocalDateTime windowFrom = LocalDateTime.now().minusDays(displayWindowDays);
        List<PriceChange> red = changeRepository
                .findByAcknowledgedFalseAndAbnormalTrueOrderByDetectedAtDesc()
                .stream().filter(c -> parentSku.equalsIgnoreCase(c.getParentSku())).toList();
        List<PriceChange> green = changeRepository
                .findByAbnormalFalseAndDetectedAtAfterOrderByDetectedAtDesc(windowFrom)
                .stream().filter(c -> parentSku.equalsIgnoreCase(c.getParentSku())).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        red.forEach(c -> out.add(changeView(c)));
        green.forEach(c -> out.add(changeView(c)));
        out.sort(Comparator.comparing(
                (Map<String, Object> m) -> (LocalDateTime) m.get("detectedAt"),
                Comparator.reverseOrder()));
        return out;
    }

    private static LocalDateTime latestDetectedAt(List<PriceChange> first, List<PriceChange> second) {
        LocalDateTime latest = null;
        for (PriceChange change : first) {
            if (latest == null || change.getDetectedAt().isAfter(latest)) {
                latest = change.getDetectedAt();
            }
        }
        for (PriceChange change : second) {
            if (latest == null || change.getDetectedAt().isAfter(latest)) {
                latest = change.getDetectedAt();
            }
        }
        return latest;
    }

    /**
     * 全量变动历史：不受展示窗口与确认状态限制（已确认红色、超出展示窗口的都能查到），
     * 可按款号 / SKU / 最近天数过滤，按检测时间倒序分页。
     *
     * @param parentSku 款号（可空）
     * @param sku       变体 SKU（可空）
     * @param days      最近天数（可空＝不限，仍受保留期物理清理约束）
     * @param page      页码（0 基）
     * @param size      每页条数
     */
    public Map<String, Object> history(String parentSku, String sku, Integer days, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_HISTORY_PAGE_SIZE);
        LocalDateTime from = (days != null && days > 0) ? LocalDateTime.now().minusDays(days) : null;

        Page<PriceChange> result = changeRepository.findAll(
                historySpec(parentSku, sku, from),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "detectedAt")));

        List<Map<String, Object>> items = new ArrayList<>();
        result.getContent().forEach(c -> items.add(changeView(c)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", result.getTotalElements());
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("totalPages", result.getTotalPages());
        out.put("retentionDays", retentionDays);
        return out;
    }

    /**
     * 单 SKU 价格走势：基线起点 → 各次变动后的价格 → 当前价，时间升序。
     *
     * <p>数据只来自「变动事件」（≥阈值才落一条），因此是阶梯走势而非每日连续曲线。
     */
    public Map<String, Object> trend(String sku) {
        String normalized = sku == null ? "" : sku.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SKU 不能为空");
        }
        PriceBaseline baseline = baselineRepository.findBySku(normalized).orElse(null);
        List<PriceChange> changes = changeRepository.findBySkuOrderByDetectedAtAsc(normalized);

        List<Map<String, Object>> points = new ArrayList<>();
        if (baseline != null && baseline.getBaselinePrice() != null) {
            points.add(trendPoint(baseline.getFirstSeenAt(), baseline.getBaselinePrice(),
                    "BASELINE", null, null, false));
        }
        for (PriceChange c : changes) {
            points.add(trendPoint(c.getDetectedAt(), c.getNewPrice(),
                    "CHANGE", c.getOldPrice(), c.getChangePct(), c.isAbnormal()));
        }
        if (baseline != null && baseline.getLastPrice() != null) {
            points.add(trendPoint(baseline.getLastSeenAt(), baseline.getLastPrice(),
                    "CURRENT", null, null, false));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", normalized);
        out.put("parentSku", baseline != null ? baseline.getParentSku()
                : (changes.isEmpty() ? null : changes.get(0).getParentSku()));
        out.put("baselinePrice", baseline != null ? baseline.getBaselinePrice() : null);
        out.put("currentPrice", baseline != null ? baseline.getLastPrice() : null);
        out.put("points", points);
        return out;
    }

    private static Map<String, Object> trendPoint(LocalDateTime at, BigDecimal price, String kind,
                                                  BigDecimal oldPrice, BigDecimal changePct, boolean abnormal) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("at", at);
        point.put("price", price);
        point.put("kind", kind);
        point.put("oldPrice", oldPrice);
        point.put("changePct", changePct);
        point.put("abnormal", abnormal);
        return point;
    }

    /** 历史查询条件：款号（忽略大小写）/ SKU / 起始时间，均为可空。 */
    private static Specification<PriceChange> historySpec(String parentSku, String sku, LocalDateTime from) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (parentSku != null && !parentSku.isBlank()) {
                predicates.add(builder.equal(
                        builder.upper(root.get("parentSku")), parentSku.trim().toUpperCase()));
            }
            if (sku != null && !sku.isBlank()) {
                predicates.add(builder.equal(root.get("sku"), sku.trim()));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("detectedAt"), from));
            }
            return predicates.isEmpty()
                    ? builder.conjunction()
                    : builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Map<String, Object> changeView(PriceChange c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", c.getId());
        view.put("sku", c.getSku());
        view.put("parentSku", c.getParentSku());
        view.put("oldPrice", c.getOldPrice());
        view.put("newPrice", c.getNewPrice());
        view.put("changePct", c.getChangePct());
        view.put("cumulativePct", c.getCumulativePct());
        view.put("detectedAt", c.getDetectedAt());
        view.put("abnormal", c.isAbnormal());
        view.put("acknowledged", c.isAcknowledged());
        return view;
    }

    /** 单款聚合（变动条数 / 是否含异常）。 */
    private static final class GroupAcc {
        private final String parentSku;
        private final List<Map<String, Object>> changes = new ArrayList<>();
        private final Set<String> skus = new LinkedHashSet<>();
        private boolean hasAbnormal;
        private int changeCount;
        private int abnormalCount;
        private int increaseCount;
        private int decreaseCount;
        private BigDecimal maxAbsChangePct = BigDecimal.ZERO;
        private LocalDateTime latestDetectedAt;

        GroupAcc(String parentSku) {
            this.parentSku = parentSku;
        }

        void add(PriceChange c, boolean abnormal) {
            changes.add(changeView(c));
            if (c.getSku() != null && !c.getSku().isBlank()) {
                skus.add(c.getSku());
            }
            if (abnormal) {
                hasAbnormal = true;
                abnormalCount++;
            }
            if (c.getChangePct() != null) {
                if (c.getChangePct().signum() > 0) {
                    increaseCount++;
                } else if (c.getChangePct().signum() < 0) {
                    decreaseCount++;
                }
                maxAbsChangePct = maxAbsChangePct.max(c.getChangePct().abs());
            }
            if (latestDetectedAt == null || c.getDetectedAt().isAfter(latestDetectedAt)) {
                latestDetectedAt = c.getDetectedAt();
            }
            changeCount++;
        }

        Map<String, Object> toView() {
            changes.sort(Comparator.comparing(
                    (Map<String, Object> m) -> (LocalDateTime) m.get("detectedAt"),
                    Comparator.reverseOrder()));
            List<Map<String, Object>> limited = changes.size() > MAX_CHANGES_PER_STYLE
                    ? changes.subList(0, MAX_CHANGES_PER_STYLE)
                    : changes;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("parentSku", parentSku);
            view.put("changeCount", changeCount);
            view.put("abnormalCount", abnormalCount);
            view.put("hasAbnormal", hasAbnormal);
            view.put("skuCount", skus.size());
            view.put("increaseCount", increaseCount);
            view.put("decreaseCount", decreaseCount);
            view.put("maxAbsChangePct", maxAbsChangePct);
            view.put("latestDetectedAt", latestDetectedAt);
            view.put("hiddenChangeCount", Math.max(0, changeCount - limited.size()));
            view.put("changes", limited);
            return view;
        }
    }
}
