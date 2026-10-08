package com.ai.walmartads.analytics.application;

import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins the latest Walmart campaign configuration snapshot to imported daily performance.
 * The snapshot is the source of truth for switches and bids; daily reports are the source
 * of truth for spend and attributed performance in the selected time window.
 */
@Service
public class CampaignStyleDiagnosisService {

    private static final Pattern PARENT_SKU = Pattern.compile("(PNK\\d+)", Pattern.CASE_INSENSITIVE);
    private static final String UNMAPPED_PARENT = "未映射款号";

    private static final DecisionThresholds DEFAULT_THRESHOLDS =
            new DecisionThresholds(5.0, 1.5, 20, 10.0);
    /** 高花费线：花费 ≥ $100 视为大额花费，升为 P0。 */
    private static final double HIGH_SPEND = 100.0;
    private static final long CACHE_TTL_NANOS = java.time.Duration.ofMinutes(5).toNanos();
    private static final int CACHE_MAX_ENTRIES = 32;

    private final NamedParameterJdbcTemplate jdbc;
    private final CampaignDecisionConfigRepository configRepository;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<HistoryCacheKey, HistoryCacheEntry> historyCache = new ConcurrentHashMap<>();

    public CampaignStyleDiagnosisService(
            NamedParameterJdbcTemplate jdbc,
            CampaignDecisionConfigRepository configRepository
    ) {
        this.jdbc = jdbc;
        this.configRepository = configRepository;
    }

    public DiagnosisResponse diagnose(LocalDate requestedStart, LocalDate requestedEnd) {
        LocalDate latestPerformanceDate = latestPerformanceDate();
        LocalDate endDate = requestedEnd == null ? latestPerformanceDate : requestedEnd;
        LocalDate startDate = requestedStart == null ? endDate.minusDays(6) : requestedStart;
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }

        DecisionThresholds thresholds = loadDecisionThresholds();
        LocalDateTime snapshotAt = snapshotUpdatedAt();
        CacheKey cacheKey = new CacheKey(
                WorkspaceContext.requireId(), startDate, endDate, snapshotAt, thresholds);
        CacheEntry cached = cache.get(cacheKey);
        long now = System.nanoTime();
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.response();
        }

        List<ConfigRow> configurations = loadConfigurations();
        Map<PerformanceKey, Performance> performanceByItem = loadPerformance(startDate, endDate);
        TreeMap<String, MutableStyle> styles = new TreeMap<>();

        for (ConfigRow config : configurations) {
            String parentSku = resolveParentSku(config);
            MutableStyle style = styles.computeIfAbsent(parentSku, MutableStyle::new);
            MutableCampaign campaign = style.campaigns.computeIfAbsent(
                    new CampaignKey(config.advertiserId(), config.adType(), config.campaignId()),
                    ignored -> new MutableCampaign(config)
            );
            if (config.itemId() != null) {
                campaign.addItem(config, performanceByItem.getOrDefault(
                        new PerformanceKey(
                                config.advertiserId(), config.adType(), config.campaignId(), config.itemId()),
                        Performance.ZERO
                ));
            }
        }

        Set<String> validParents = loadValidParentSkus();
        boolean filterByProductLibrary = !validParents.isEmpty();

        List<StyleDiagnosis> rows = styles.entrySet().stream()
                .filter(entry -> !filterByProductLibrary
                        || validParents.contains(entry.getKey().toUpperCase(Locale.ROOT)))
                .map(entry -> entry.getValue().toRow())
                .sorted(Comparator.comparing(StyleDiagnosis::parentSku, String.CASE_INSENSITIVE_ORDER))
                .toList();
        DiagnosisResponse response = new DiagnosisResponse(
                new Meta(
                        startDate,
                        endDate,
                        snapshotAt,
                        !configurations.isEmpty(),
                        configurations.size(),
                        loadCoveredDates(startDate, endDate),
                        true
                ),
                Summary.from(rows),
                rows,
                buildPriorityActions(rows, thresholds),
                buildMethodology(thresholds),
                buildSources(startDate, endDate, snapshotAt, configurations.size())
        );
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(cacheKey, new CacheEntry(response, now + CACHE_TTL_NANOS));
        return response;
    }

    public DiagnosisResponse diagnoseSummary(LocalDate requestedStart, LocalDate requestedEnd) {
        return diagnose(requestedStart, requestedEnd).withoutItemDetails();
    }

    public StyleDiagnosis styleDiagnosis(
            LocalDate requestedStart,
            LocalDate requestedEnd,
            String parentSku
    ) {
        if (parentSku == null || parentSku.isBlank()) {
            throw new IllegalArgumentException("parentSku is required");
        }
        return diagnose(requestedStart, requestedEnd).styles().stream()
                .filter(style -> style.parentSku().equalsIgnoreCase(parentSku.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No diagnosis found for parentSku " + parentSku));
    }

    /**
     * Returns every non-live campaign associated with one parent SKU, aggregated across
     * the complete AD_ITEM history stored locally. Current diagnosis date filters do not
     * apply here: this endpoint represents the campaign lifecycle archive.
     */
    public HistoryResponse history(String requestedParentSku) {
        if (requestedParentSku == null || requestedParentSku.isBlank()) {
            throw new IllegalArgumentException("parentSku is required");
        }
        String parentSku = requestedParentSku.trim().toUpperCase(Locale.ROOT);
        HistoryCacheKey historyCacheKey = new HistoryCacheKey(WorkspaceContext.requireId(), parentSku);
        long now = System.nanoTime();
        HistoryCacheEntry cached = historyCache.get(historyCacheKey);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.response();
        }

        Map<HistoryCampaignKey, HistoricalCampaignConfig> configurations = loadHistoricalCampaignConfigurations();
        List<HistoryItemRow> itemRows = loadHistoricalItemRows(parentSku);
        Map<HistoryCampaignKey, List<HistoryItem>> itemsByCampaign = new LinkedHashMap<>();
        Map<HistoryCampaignKey, HistoryItemRow> fallbackByCampaign = new LinkedHashMap<>();
        for (HistoryItemRow row : itemRows) {
            itemsByCampaign.computeIfAbsent(row.campaignKey(), ignored -> new ArrayList<>()).add(row.item());
            fallbackByCampaign.putIfAbsent(row.campaignKey(), row);
        }

        Set<HistoryCampaignKey> campaignKeys = new LinkedHashSet<>(itemsByCampaign.keySet());
        configurations.forEach((key, config) -> {
            if (!isCurrentCampaignStatus(campaignStatus(config.sourceCampaignStatus()))
                    && parentSku.equals(extractParentSku(config.campaignName()))) {
                campaignKeys.add(key);
            }
        });

        List<HistoricalCampaign> campaigns = new ArrayList<>();
        for (HistoryCampaignKey key : campaignKeys) {
            HistoricalCampaignConfig config = configurations.get(key);
            HistoryItemRow fallback = fallbackByCampaign.get(key);
            String sourceStatus = config == null
                    ? (fallback == null ? null : fallback.sourceCampaignStatus())
                    : config.sourceCampaignStatus();
            String status = campaignStatus(sourceStatus);
            if (isCurrentCampaignStatus(status)) {
                continue;
            }
            List<HistoryItem> items = itemsByCampaign.getOrDefault(key, List.of()).stream()
                    .sorted(Comparator
                            .comparingDouble((HistoryItem item) -> item.metrics().spend()).reversed()
                            .thenComparing(item -> item.sku() == null ? item.itemId() : item.sku(),
                                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            HistoryMetrics metrics = HistoryMetrics.fromItems(items);
            LocalDate firstDate = items.stream().map(HistoryItem::firstDate)
                    .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            LocalDate lastDate = items.stream().map(HistoryItem::lastDate)
                    .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
            campaigns.add(new HistoricalCampaign(
                    config == null ? (fallback == null ? 0 : fallback.advertiserId()) : config.advertiserId(),
                    key.adType(),
                    key.campaignId(),
                    config == null ? fallbackText(fallback, HistoryItemRow::campaignName) : config.campaignName(),
                    config == null ? fallbackText(fallback, HistoryItemRow::campaignType) : config.campaignType(),
                    config == null ? fallbackText(fallback, HistoryItemRow::targetingType) : config.targetingType(),
                    status,
                    sourceStatus,
                    config == null ? 0 : config.dailyBudget(),
                    config == null ? null : config.biddingStrategy(),
                    firstDate,
                    lastDate,
                    metrics,
                    items
            ));
        }
        campaigns.sort(Comparator
                .comparing(HistoricalCampaign::lastDate,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(HistoricalCampaign::campaignName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        HistoryResponse response = new HistoryResponse(
                parentSku,
                LocalDateTime.now(),
                HistorySummary.from(campaigns),
                List.copyOf(campaigns)
        );
        if (historyCache.size() >= CACHE_MAX_ENTRIES) {
            historyCache.clear();
        }
        historyCache.put(historyCacheKey, new HistoryCacheEntry(response, now + CACHE_TTL_NANOS));
        return response;
    }

    private Map<HistoryCampaignKey, HistoricalCampaignConfig> loadHistoricalCampaignConfigurations() {
        Map<HistoryCampaignKey, HistoricalCampaignConfig> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select campaign.advertiser_id,
                       campaign.ad_type,
                       cast(campaign.campaign_id as char) as campaign_id,
                       campaign.campaign_name,
                       campaign.campaign_type,
                       campaign.targeting_type,
                       campaign.campaign_status,
                       campaign.daily_budget,
                       campaign.bidding_strategy
                  from wm_ads_lx_ad_report_raw campaign
                  join (
                       select advertiser_id, ad_type, max(report_date) as report_date
                         from wm_ads_lx_ad_report_raw
                        where report_level = 'CAMPAIGN'
                        group by advertiser_id, ad_type
                  ) latest
                    on latest.report_date = campaign.report_date
                   and latest.advertiser_id = campaign.advertiser_id
                   and latest.ad_type = campaign.ad_type
                 where campaign.report_level = 'CAMPAIGN'
                   and campaign.campaign_id is not null
                """, new MapSqlParameterSource()).forEach(row -> {
            HistoryCampaignKey key = new HistoryCampaignKey(text(row, "ad_type"), text(row, "campaign_id"));
            result.put(key, new HistoricalCampaignConfig(
                    longNumber(row, "advertiser_id"),
                    text(row, "campaign_name"),
                    text(row, "campaign_type"),
                    text(row, "targeting_type"),
                    text(row, "campaign_status"),
                    number(row, "daily_budget"),
                    text(row, "bidding_strategy")
            ));
        });
        return result;
    }

    private List<HistoryItemRow> loadHistoricalItemRows(String parentSku) {
        return jdbc.queryForList("""
                select max(ads.advertiser_id) as advertiser_id,
                       ads.ad_type,
                       cast(ads.campaign_id as char) as campaign_id,
                       max(ads.campaign_name) as campaign_name,
                       max(ads.campaign_type) as campaign_type,
                       max(ads.targeting_type) as targeting_type,
                       max(ads.campaign_status) as campaign_status,
                       ads.item_id,
                       max(ads.ad_item_id) as ad_item_id,
                       max(ads.sku) as sku,
                       max(ads.ad_group_id) as ad_group_id,
                       max(ads.ad_group_name) as ad_group_name,
                       max(ads.ad_name) as item_name,
                       min(ads.report_date) as first_date,
                       max(ads.report_date) as last_date,
                       sum(ads.impressions) as impressions,
                       sum(ads.clicks) as clicks,
                       sum(ads.ad_spend) as spend,
                       sum(ads.attributed_orders) as ad_orders,
                       sum(ads.attributed_sales) as ad_sales
                  from wm_ads_lx_ad_report_raw ads
                 where ads.report_level = 'AD_ITEM'
                   and ads.parent_sku = :parentSku
                   and ads.campaign_id is not null
                   and ads.item_id is not null
                 group by ads.ad_type, cast(ads.campaign_id as char), ads.item_id
                """, new MapSqlParameterSource("parentSku", parentSku)).stream()
                .map(row -> {
                    HistoryCampaignKey key = new HistoryCampaignKey(
                            text(row, "ad_type"), text(row, "campaign_id"));
                    HistoryMetrics metrics = HistoryMetrics.fromValues(
                            number(row, "impressions"),
                            number(row, "clicks"),
                            number(row, "spend"),
                            number(row, "ad_orders"),
                            number(row, "ad_sales")
                    );
                    return new HistoryItemRow(
                            key,
                            longNumber(row, "advertiser_id"),
                            text(row, "campaign_name"),
                            text(row, "campaign_type"),
                            text(row, "targeting_type"),
                            text(row, "campaign_status"),
                            new HistoryItem(
                                    text(row, "sku"),
                                    text(row, "item_id"),
                                    text(row, "ad_item_id"),
                                    text(row, "ad_group_id"),
                                    text(row, "ad_group_name"),
                                    text(row, "item_name"),
                                    date(row.get("first_date")),
                                    date(row.get("last_date")),
                                    metrics
                            )
                    );
                })
                .toList();
    }

    private static String extractParentSku(String campaignName) {
        if (!notBlank(campaignName)) {
            return null;
        }
        Matcher matcher = PARENT_SKU.matcher(campaignName);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static <T> String fallbackText(T value, java.util.function.Function<T, String> extractor) {
        return value == null ? null : extractor.apply(value);
    }

    /** 按款号派生 P0/P1 优先执行清单：规则与前端 decide() 同源，高花费优先升 P0。 */
    private List<PriorityAction> buildPriorityActions(
            List<StyleDiagnosis> rows,
            DecisionThresholds thresholds
    ) {
        List<PriorityAction> actions = new ArrayList<>();
        for (StyleDiagnosis row : rows) {
            double spend = row.spend();
            double roas = spend > 0 ? row.adSales() / spend : 0;
            String decision = decide(spend, row.adSales(), row.adOrders(), row.clicks(), thresholds);
            boolean noServing = row.actualServingItemCount() <= 0;
            boolean highSpend = spend >= HIGH_SPEND;

            switch (decision) {
                case "reduce" -> actions.add(new PriorityAction(
                        highSpend ? "P0" : "P1",
                        "reduce",
                        row.parentSku(),
                        spend,
                        highSpend
                                ? "降耗且花费较高，优先处理：降低低效 Item 竞价或暂停低效商品，避免继续消耗。"
                                : "ROAS 低于降耗线，建议降低竞价或暂停低效 Item。"));
                case "scale" -> actions.add(new PriorityAction(
                        highSpend ? "P0" : "P1",
                        "scale",
                        row.parentSku(),
                        row.adSales(),
                        highSpend
                                ? "放量且已投入较高，优先处理：小步增加预算或竞价并持续观察 ROAS。"
                                : "ROAS 达标且订单充足，可小步增加预算或竞价。"));
                case "optimize" -> actions.add(new PriorityAction(
                        "P1",
                        "optimize",
                        row.parentSku(),
                        row.adSales(),
                        "ROAS 位于优化区间，先优化商品、定向或竞价，再决定是否放量。"));
                default -> {
                    if (row.campaignCount() > 0 && noServing && spend > 0) {
                        actions.add(new PriorityAction(
                                "P1",
                                "inactive",
                                row.parentSku(),
                                spend,
                                "配置了 Item 但当前没有实际投放且有花费，建议核查活动和 Item 开关。"));
                    }
                }
            }
        }
        actions.sort(Comparator
                .comparing((PriorityAction action) -> "P0".equals(action.priority()) ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(PriorityAction::impact).reversed()));
        return actions;
    }

    private static String decide(
            double spend,
            double adSales,
            double adOrders,
            double clicks,
            DecisionThresholds thresholds
    ) {
        boolean enough = clicks >= thresholds.minClicks() && spend >= thresholds.minSpend();
        if (!enough) return "collect";
        double roas = spend > 0 ? adSales / spend : 0;
        if (roas >= thresholds.targetRoas() && adOrders >= 3) return "scale";
        if (roas < thresholds.floorRoas() || adOrders == 0) return "reduce";
        return "optimize";
    }

    private Methodology buildMethodology(DecisionThresholds thresholds) {
        return new Methodology(
                thresholds.targetRoas(),
                thresholds.floorRoas(),
                thresholds.minClicks(),
                thresholds.minSpend(),
                "判定规则：混合广告类型按已配置阈值的保守组合判定；点击≥" + thresholds.minClicks()
                        + " 且花费≥$" + thresholds.minSpend()
                        + " 才参与决策；ROAS≥" + thresholds.targetRoas()
                        + " 且广告订单≥3 → 建议放量；ROAS<" + thresholds.floorRoas() + " 或广告订单=0 → 建议降耗；"
                        + "其余 → 继续优化；样本不足 → 数据不足。",
                "主绩效口径：仅当前投放活动参与款号诊断；暂停、结束、提案等历史广告按所选日期保留明细，但不计入当前花费、归因销售、订单、点击与优先建议。ROAS=当前投放活动归因销售÷广告费。",
                "配置口径：活动开关、Item 开关、竞价与预算按广告账号和广告类型，分别读取最新 CAMPAIGN 与 AD_ITEM 快照。",
                "库存口径：广告诊断不直接引用库存；库存保护需结合库存管理页的覆盖与补货建议综合判断。"
        );
    }

    private DecisionThresholds loadDecisionThresholds() {
        List<CampaignDecisionConfig> configs = configRepository.findAll();
        if (configs.isEmpty()) {
            return DEFAULT_THRESHOLDS;
        }
        return new DecisionThresholds(
                configs.stream().mapToDouble(CampaignDecisionConfig::getTargetRoas).max()
                        .orElse(DEFAULT_THRESHOLDS.targetRoas()),
                configs.stream().mapToDouble(CampaignDecisionConfig::getFloorRoas).min()
                        .orElse(DEFAULT_THRESHOLDS.floorRoas()),
                configs.stream().mapToInt(CampaignDecisionConfig::getMinClicks).max()
                        .orElse(DEFAULT_THRESHOLDS.minClicks()),
                configs.stream().mapToDouble(CampaignDecisionConfig::getMinSpend).max()
                        .orElse(DEFAULT_THRESHOLDS.minSpend())
        );
    }

    private List<SourceInfo> buildSources(LocalDate startDate, LocalDate endDate, LocalDateTime snapshotAt, int configuredRows) {
        return List.of(
                new SourceInfo("领星活动配置", "configuration",
                        "wm_ads_lx_ad_report（CAMPAIGN + AD_ITEM 最新日期）"
                                + (snapshotAt == null ? "" : "，更新于 " + snapshotAt.toString().replace("T", " "))
                                + "；共 " + configuredRows + " 行配置。"),
                new SourceInfo("广告表现日报", "performance",
                        "wm_ads_lx_ad_report（AD_ITEM）按 " + startDate + " 至 " + endDate + " 汇总"),
                new SourceInfo("SKU 映射", "mapping",
                        "wm_ads_sku_mapping（current_version=true）把 Item 关联到款号"),
                new SourceInfo("产品库过滤", "filter",
                        "wm_ads_product_record（current_version=true）款号集合过滤；产品库为空时兜底显示全部")
        );
    }

    private List<ConfigRow> loadConfigurations() {
        return jdbc.queryForList("""
                select campaign.advertiser_id,
                       campaign.ad_type,
                       campaign.campaign_id,
                       campaign.campaign_name,
                       campaign.campaign_type,
                       campaign.targeting_type,
                       campaign.campaign_status,
                       campaign.daily_budget,
                       campaign.bidding_strategy,
                       item.ad_group_id,
                       item.ad_group_status,
                       item.item_id,
                       item.ad_item_id,
                       item.bid,
                       item.ad_status,
                       item.ad_name as item_name,
                       item.review_status,
                       item.sku,
                       item.parent_sku
                  from wm_ads_lx_ad_report_raw campaign
                  join (
                       select advertiser_id, ad_type, max(report_date) as report_date
                         from wm_ads_lx_ad_report_raw
                        where report_level = 'CAMPAIGN'
                        group by advertiser_id, ad_type
                  ) latest
                    on latest.report_date = campaign.report_date
                   and latest.advertiser_id = campaign.advertiser_id
                   and latest.ad_type = campaign.ad_type
                  left join (
                       select advertiser_id, ad_type, max(report_date) as report_date
                         from wm_ads_lx_ad_report_raw
                        where report_level = 'AD_ITEM'
                        group by advertiser_id, ad_type
                  ) latest_item
                    on latest_item.advertiser_id = campaign.advertiser_id
                   and latest_item.ad_type = campaign.ad_type
                  left join wm_ads_lx_ad_report_raw item
                    on item.report_date = latest_item.report_date
                    and item.advertiser_id = campaign.advertiser_id
                   and item.ad_type = campaign.ad_type
                   and item.campaign_id = campaign.campaign_id
                   and item.report_level = 'AD_ITEM'
                 where campaign.report_level = 'CAMPAIGN'
                 order by campaign.campaign_name, item.item_id
                """, new MapSqlParameterSource()).stream()
                .map(row -> new ConfigRow(
                        longNumber(row, "advertiser_id"),
                        text(row, "ad_type"),
                        text(row, "campaign_id"),
                        text(row, "campaign_name"),
                        text(row, "campaign_type"),
                        text(row, "targeting_type"),
                        text(row, "campaign_status"),
                        number(row, "daily_budget"),
                        text(row, "bidding_strategy"),
                        text(row, "ad_group_id"),
                        text(row, "ad_group_status"),
                        text(row, "item_id"),
                        text(row, "ad_item_id"),
                        number(row, "bid"),
                        text(row, "ad_status"),
                        text(row, "item_name"),
                        text(row, "review_status"),
                        text(row, "sku"),
                        text(row, "parent_sku")
                ))
                .toList();
    }

    private Map<PerformanceKey, Performance> loadPerformance(LocalDate startDate, LocalDate endDate) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("endDate", endDate);
        Map<PerformanceKey, Performance> result = new HashMap<>();
        jdbc.queryForList("""
                select ads.advertiser_id,
                       ads.ad_type,
                       cast(ads.campaign_id as char) as campaign_id,
                       ads.item_id,
                       sum(ads.impressions) as impressions,
                       sum(ads.clicks) as clicks,
                       sum(ads.ad_spend) as spend,
                       sum(ads.attributed_orders) as ad_orders,
                       sum(ads.attributed_sales) as ad_sales
                  from wm_ads_lx_ad_report_raw ads
                 where ads.report_level = 'AD_ITEM'
                   and ads.report_date between :startDate and :endDate
                    and ads.campaign_id is not null
                    and ads.item_id is not null
                    and (ads.impressions <> 0
                         or ads.clicks <> 0
                         or ads.ad_spend <> 0
                         or ads.attributed_orders <> 0
                         or ads.attributed_sales <> 0)
                  group by ads.advertiser_id, ads.ad_type, cast(ads.campaign_id as char), ads.item_id
                """, parameters).forEach(row -> result.put(
                        new PerformanceKey(
                                longNumber(row, "advertiser_id"),
                                text(row, "ad_type"),
                                text(row, "campaign_id"),
                                text(row, "item_id")),
                        new Performance(
                                number(row, "impressions"),
                                number(row, "clicks"),
                                number(row, "spend"),
                                number(row, "ad_orders"),
                                number(row, "ad_sales")
                        )
                ));
        return result;
    }

    /**
     * 产品库（wm_ads_product_record，current_version=1）中有效的父 SKU 集合。
     * 仅当该集合非空时才启用过滤：产品库为空（size===0）时兜底显示全部，避免误杀。
     */
    private Set<String> loadValidParentSkus() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select distinct upper(parent_sku) as parent_sku
                  from wm_ads_product_record
                 where current_version = true
                   and parent_sku is not null
                   and parent_sku <> ''
                """, new MapSqlParameterSource());
        Set<String> parents = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String value = text(row, "parent_sku");
            if (value != null && !value.isBlank()) {
                parents.add(value);
            }
        }
        return parents;
    }

    private LocalDate latestPerformanceDate() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select max(ads.report_date) as latest_date
                  from wm_ads_lx_ad_report_raw ads
                 where ads.report_level = 'AD_ITEM'
                """, new MapSqlParameterSource());
        LocalDate result = rows.isEmpty() ? null : date(rows.getFirst().get("latest_date"));
        return result == null ? LocalDate.now() : result;
    }

    private LocalDateTime snapshotUpdatedAt() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select max(synced_at) as snapshot_at
                  from wm_ads_lx_ad_report_raw
                 where report_level = 'CAMPAIGN'
                """, new MapSqlParameterSource());
        if (rows.isEmpty()) {
            return null;
        }
        Object value = rows.getFirst().get("snapshot_at");
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    private List<LocalDate> loadCoveredDates(LocalDate startDate, LocalDate endDate) {
        return jdbc.queryForList("""
                select distinct report_date
                  from wm_ads_lx_ad_report_raw
                 where report_level = 'AD_ITEM'
                   and report_date between :startDate and :endDate
                 order by report_date
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("endDate", endDate), LocalDate.class);
    }

    private static String resolveParentSku(ConfigRow row) {
        if (notBlank(row.parentSku())) {
            return row.parentSku();
        }
        if (notBlank(row.campaignName())) {
            Matcher matcher = PARENT_SKU.matcher(row.campaignName());
            if (matcher.find()) {
                return matcher.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return UNMAPPED_PARENT;
    }

    private static String campaignStatus(String sourceStatus) {
        if (sourceStatus == null) {
            return "状态未知";
        }
        return switch (sourceStatus.toLowerCase(Locale.ROOT)) {
            case "live" -> "投放中";
            case "paused" -> "已暂停";
            case "enabled" -> "已启用";
            case "scheduled", "rescheduled" -> "已排期";
            case "completed", "ended" -> "已结束";
            case "archived" -> "已归档";
            case "disabled", "inactive" -> "已停用";
            case "draft" -> "草稿";
            case "rejected" -> "已拒绝";
            case "proposal" -> "提案";
            default -> "状态未知";
        };
    }

    static boolean isCurrentCampaignStatus(String status) {
        return "投放中".equals(status);
    }

    private static String itemStatus(String sourceStatus) {
        return "enabled".equalsIgnoreCase(sourceStatus) ? "Item已开启" : "Item已关闭";
    }

    private static String adGroupStatus(String sourceStatus) {
        return "enabled".equalsIgnoreCase(sourceStatus) || "live".equalsIgnoreCase(sourceStatus)
                ? "广告组已开启"
                : "广告组未开启";
    }

    private static String actualStatus(
            String campaignStatus,
            String adGroupStatus,
            String itemStatus,
            String reviewStatus
    ) {
        if (!"投放中".equals(campaignStatus)) {
            return "已暂停".equals(campaignStatus) ? "活动已暂停" : "活动未投放";
        }
        if (!"广告组已开启".equals(adGroupStatus)) {
            return "广告组未开启";
        }
        if (!"Item已开启".equals(itemStatus)) {
            return "Item未开启";
        }
        if (!"approved".equalsIgnoreCase(reviewStatus)) {
            return "pending".equalsIgnoreCase(reviewStatus) ? "审核中" : "审核未通过";
        }
        return "实际投放";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static double number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static long longNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return value == null ? null : LocalDate.parse(String.valueOf(value));
    }

    public record DiagnosisResponse(
            Meta meta,
            Summary summary,
            List<StyleDiagnosis> styles,
            List<PriorityAction> priorityActions,
            Methodology methodology,
            List<SourceInfo> sources
    ) {
        DiagnosisResponse withoutItemDetails() {
            return new DiagnosisResponse(
                    new Meta(meta.startDate(), meta.endDate(), meta.snapshotUpdatedAt(),
                            meta.snapshotAvailable(), meta.configuredRows(), meta.coveredDates(), false),
                    summary,
                    styles.stream().map(StyleDiagnosis::withoutItemDetails).toList(),
                    priorityActions,
                    methodology,
                    sources
            );
        }
    }

    public record Meta(
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime snapshotUpdatedAt,
            boolean snapshotAvailable,
            int configuredRows,
            List<LocalDate> coveredDates,
            boolean detailsIncluded
    ) {
    }

    public record Summary(
            int styleCount,
            int currentStyleCount,
            int historyOnlyStyleCount,
            int campaignCount,
            int liveCampaignCount,
            int pausedCampaignCount,
            int historicalCampaignCount,
            int configuredItemCount,
            int enabledItemCount,
            int actualServingItemCount,
            double spend,
            double adSales,
            double adOrders,
            double clicks
    ) {
        static Summary from(List<StyleDiagnosis> styles) {
            Set<String> currentCampaigns = new LinkedHashSet<>();
            Set<String> historicalCampaigns = new LinkedHashSet<>();
            int liveCampaigns = 0;
            int pausedCampaigns = 0;
            int currentStyles = 0;
            int historyOnlyStyles = 0;
            int items = 0;
            int enabled = 0;
            int actualServing = 0;
            double spend = 0;
            double adSales = 0;
            double adOrders = 0;
            double clicks = 0;
            for (StyleDiagnosis style : styles) {
                if (style.campaignCount() > 0) {
                    currentStyles++;
                } else if (style.historicalCampaignCount() > 0) {
                    historyOnlyStyles++;
                }
                items += style.configuredItemCount();
                enabled += style.enabledItemCount();
                actualServing += style.actualServingItemCount();
                spend += style.spend();
                adSales += style.adSales();
                adOrders += style.adOrders();
                clicks += style.clicks();
                for (CampaignDiagnosis campaign : style.campaigns()) {
                    String campaignKey = campaign.advertiserId() + "\u0000" + campaign.adType()
                            + "\u0000" + campaign.campaignId();
                    if (isCurrentCampaignStatus(campaign.campaignStatus())) {
                        if (currentCampaigns.add(campaignKey)) {
                            liveCampaigns++;
                        }
                    } else if (historicalCampaigns.add(campaignKey)) {
                        if ("已暂停".equals(campaign.campaignStatus())) {
                            pausedCampaigns++;
                        }
                    }
                }
            }
            return new Summary(
                    styles.size(), currentStyles, historyOnlyStyles,
                    currentCampaigns.size(), liveCampaigns, pausedCampaigns, historicalCampaigns.size(),
                    items, enabled, actualServing, spend, adSales, adOrders, clicks
            );
        }
    }

    public record StyleDiagnosis(
            String parentSku,
            int campaignCount,
            int historicalCampaignCount,
            int configuredItemCount,
            int enabledItemCount,
            int actualServingItemCount,
            double spend,
            double adSales,
            double adOrders,
            double clicks,
            List<CampaignDiagnosis> campaigns
    ) {
        StyleDiagnosis withoutItemDetails() {
            return new StyleDiagnosis(parentSku, campaignCount, historicalCampaignCount,
                    configuredItemCount, enabledItemCount, actualServingItemCount,
                    spend, adSales, adOrders, clicks,
                    campaigns.stream().map(CampaignDiagnosis::withoutItems).toList());
        }
    }

    /** 优先执行清单条目：P0/P1、动作类型、影响金额（美元）与行动说明。 */
    public record PriorityAction(
            String priority,
            String type,
            String parentSku,
            double impact,
            String message
    ) {
    }

    /** 口径与判定规则：与前端 decide() / DashboardDataService.Defaults 同源的阈值说明。 */
    public record Methodology(
            double targetRoas,
            double floorRoas,
            int minClicks,
            double minSpend,
            String decisionRule,
            String performanceScope,
            String configurationScope,
            String inventoryScope
    ) {
    }

    /** 数据源与质量检查：数据来源、口径与对账说明。 */
    public record SourceInfo(
            String name,
            String kind,
            String detail
    ) {
    }

    public record HistoryResponse(
            String parentSku,
            LocalDateTime generatedAt,
            HistorySummary summary,
            List<HistoricalCampaign> campaigns
    ) {
    }

    public record HistorySummary(
            int campaignCount,
            LocalDate firstDate,
            LocalDate lastDate,
            HistoryMetrics metrics
    ) {
        static HistorySummary from(List<HistoricalCampaign> campaigns) {
            LocalDate firstDate = campaigns.stream().map(HistoricalCampaign::firstDate)
                    .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            LocalDate lastDate = campaigns.stream().map(HistoricalCampaign::lastDate)
                    .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
            return new HistorySummary(campaigns.size(), firstDate, lastDate,
                    HistoryMetrics.fromCampaigns(campaigns));
        }
    }

    public record HistoricalCampaign(
            long advertiserId,
            String adType,
            String campaignId,
            String campaignName,
            String campaignType,
            String targetingType,
            String campaignStatus,
            String sourceCampaignStatus,
            double dailyBudget,
            String biddingStrategy,
            LocalDate firstDate,
            LocalDate lastDate,
            HistoryMetrics metrics,
            List<HistoryItem> items
    ) {
    }

    public record HistoryItem(
            String sku,
            String itemId,
            String adItemId,
            String adGroupId,
            String adGroupName,
            String itemName,
            LocalDate firstDate,
            LocalDate lastDate,
            HistoryMetrics metrics
    ) {
    }

    public record HistoryMetrics(
            double impressions,
            double clicks,
            double spend,
            double adOrders,
            double adSales,
            double ctr,
            double cpc,
            double cvr,
            double roas,
            double acos
    ) {
        static HistoryMetrics fromValues(
                double impressions,
                double clicks,
                double spend,
                double adOrders,
                double adSales
        ) {
            return new HistoryMetrics(
                    impressions,
                    clicks,
                    spend,
                    adOrders,
                    adSales,
                    impressions > 0 ? clicks / impressions : 0,
                    clicks > 0 ? spend / clicks : 0,
                    clicks > 0 ? adOrders / clicks : 0,
                    spend > 0 ? adSales / spend : 0,
                    adSales > 0 ? spend / adSales : 0
            );
        }

        static HistoryMetrics fromItems(List<HistoryItem> items) {
            return fromValues(
                    items.stream().mapToDouble(item -> item.metrics().impressions()).sum(),
                    items.stream().mapToDouble(item -> item.metrics().clicks()).sum(),
                    items.stream().mapToDouble(item -> item.metrics().spend()).sum(),
                    items.stream().mapToDouble(item -> item.metrics().adOrders()).sum(),
                    items.stream().mapToDouble(item -> item.metrics().adSales()).sum()
            );
        }

        static HistoryMetrics fromCampaigns(List<HistoricalCampaign> campaigns) {
            return fromValues(
                    campaigns.stream().mapToDouble(campaign -> campaign.metrics().impressions()).sum(),
                    campaigns.stream().mapToDouble(campaign -> campaign.metrics().clicks()).sum(),
                    campaigns.stream().mapToDouble(campaign -> campaign.metrics().spend()).sum(),
                    campaigns.stream().mapToDouble(campaign -> campaign.metrics().adOrders()).sum(),
                    campaigns.stream().mapToDouble(campaign -> campaign.metrics().adSales()).sum()
            );
        }
    }

    public record CampaignDiagnosis(
            long advertiserId,
            String adType,
            String campaignId,
            String campaignName,
            String campaignType,
            String targetingType,
            String campaignStatus,
            String sourceCampaignStatus,
            double dailyBudget,
            String biddingStrategy,
            int configuredItemCount,
            int enabledItemCount,
            int actualServingItemCount,
            double spend,
            double adSales,
            double adOrders,
            double clicks,
            List<ItemDiagnosis> items
    ) {
        CampaignDiagnosis withoutItems() {
            return new CampaignDiagnosis(advertiserId, adType, campaignId, campaignName,
                    campaignType, targetingType, campaignStatus, sourceCampaignStatus,
                    dailyBudget, biddingStrategy, configuredItemCount, enabledItemCount,
                    actualServingItemCount, spend, adSales, adOrders, clicks, List.of());
        }
    }

    public record ItemDiagnosis(
            String sku,
            String itemId,
            String adItemId,
            String adGroupId,
            String adGroupStatus,
            String itemName,
            String reviewStatus,
            double bid,
            String itemStatus,
            String actualStatus,
            double impressions,
            double clicks,
            double spend,
            double adOrders,
            double adSales
    ) {
    }

    private record ConfigRow(
            long advertiserId,
            String adType,
            String campaignId,
            String campaignName,
            String campaignType,
            String targetingType,
            String sourceCampaignStatus,
            double dailyBudget,
            String biddingStrategy,
            String adGroupId,
            String sourceAdGroupStatus,
            String itemId,
            String adItemId,
            double bid,
            String sourceItemStatus,
            String itemName,
            String reviewStatus,
            String sku,
            String parentSku
    ) {
    }

    private record CampaignKey(long advertiserId, String adType, String campaignId) {
    }

    private record HistoryCampaignKey(String adType, String campaignId) {
    }

    private record PerformanceKey(
            long advertiserId,
            String adType,
            String campaignId,
            String itemId
    ) {
    }

    private record DecisionThresholds(
            double targetRoas,
            double floorRoas,
            int minClicks,
            double minSpend
    ) {
    }

    private record CacheKey(
            long workspaceId,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime snapshotAt,
            DecisionThresholds thresholds
    ) {
    }

    private record HistoryCacheKey(long workspaceId, String parentSku) {
    }

    private record CacheEntry(DiagnosisResponse response, long expiresAtNanos) {
    }

    private record HistoryCacheEntry(HistoryResponse response, long expiresAtNanos) {
    }

    private record HistoricalCampaignConfig(
            long advertiserId,
            String campaignName,
            String campaignType,
            String targetingType,
            String sourceCampaignStatus,
            double dailyBudget,
            String biddingStrategy
    ) {
    }

    private record HistoryItemRow(
            HistoryCampaignKey campaignKey,
            long advertiserId,
            String campaignName,
            String campaignType,
            String targetingType,
            String sourceCampaignStatus,
            HistoryItem item
    ) {
    }

    private record Performance(
            double impressions,
            double clicks,
            double spend,
            double adOrders,
            double adSales
    ) {
        private static final Performance ZERO = new Performance(0, 0, 0, 0, 0);
    }

    private static final class MutableStyle {
        private final String parentSku;
        private final Map<CampaignKey, MutableCampaign> campaigns = new LinkedHashMap<>();

        private MutableStyle(String parentSku) {
            this.parentSku = parentSku;
        }

        private StyleDiagnosis toRow() {
            List<CampaignDiagnosis> rows = campaigns.values().stream()
                    .map(MutableCampaign::toRow)
                    .sorted(Comparator
                            .comparing((CampaignDiagnosis campaign) -> !isCurrentCampaignStatus(campaign.campaignStatus()))
                            .thenComparing(CampaignDiagnosis::campaignName,
                                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            List<CampaignDiagnosis> currentRows = rows.stream()
                    .filter(campaign -> isCurrentCampaignStatus(campaign.campaignStatus()))
                    .toList();
            return new StyleDiagnosis(
                    parentSku,
                    currentRows.size(),
                    rows.size() - currentRows.size(),
                    currentRows.stream().mapToInt(CampaignDiagnosis::configuredItemCount).sum(),
                    currentRows.stream().mapToInt(CampaignDiagnosis::enabledItemCount).sum(),
                    currentRows.stream().mapToInt(CampaignDiagnosis::actualServingItemCount).sum(),
                    currentRows.stream().mapToDouble(CampaignDiagnosis::spend).sum(),
                    currentRows.stream().mapToDouble(CampaignDiagnosis::adSales).sum(),
                    currentRows.stream().mapToDouble(CampaignDiagnosis::adOrders).sum(),
                    currentRows.stream().mapToDouble(CampaignDiagnosis::clicks).sum(),
                    rows
            );
        }
    }

    private static final class MutableCampaign {
        private final ConfigRow configuration;
        private final List<ItemDiagnosis> items = new ArrayList<>();

        private MutableCampaign(ConfigRow configuration) {
            this.configuration = configuration;
        }

        private void addItem(ConfigRow config, Performance performance) {
            String campaignStatus = campaignStatus(config.sourceCampaignStatus());
            String adGroupStatus = adGroupStatus(config.sourceAdGroupStatus());
            String itemStatus = itemStatus(config.sourceItemStatus());
            items.add(new ItemDiagnosis(
                    config.sku(),
                    config.itemId(),
                    config.adItemId(),
                    config.adGroupId(),
                    adGroupStatus,
                    config.itemName(),
                    config.reviewStatus(),
                    config.bid(),
                    itemStatus,
                    actualStatus(campaignStatus, adGroupStatus, itemStatus, config.reviewStatus()),
                    performance.impressions(),
                    performance.clicks(),
                    performance.spend(),
                    performance.adOrders(),
                    performance.adSales()
            ));
        }

        private CampaignDiagnosis toRow() {
            List<ItemDiagnosis> rows = items.stream()
                    .sorted(Comparator.comparing(
                            item -> item.sku() == null ? item.itemId() : item.sku(),
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .toList();
            String status = campaignStatus(configuration.sourceCampaignStatus());
            return new CampaignDiagnosis(
                    configuration.advertiserId(),
                    configuration.adType(),
                    configuration.campaignId(),
                    configuration.campaignName(),
                    configuration.campaignType(),
                    configuration.targetingType(),
                    status,
                    configuration.sourceCampaignStatus(),
                    configuration.dailyBudget(),
                    configuration.biddingStrategy(),
                    rows.size(),
                    (int) rows.stream().filter(item -> "Item已开启".equals(item.itemStatus())).count(),
                    (int) rows.stream().filter(item -> "实际投放".equals(item.actualStatus())).count(),
                    rows.stream().mapToDouble(ItemDiagnosis::spend).sum(),
                    rows.stream().mapToDouble(ItemDiagnosis::adSales).sum(),
                    rows.stream().mapToDouble(ItemDiagnosis::adOrders).sum(),
                    rows.stream().mapToDouble(ItemDiagnosis::clicks).sum(),
                    rows
            );
        }
    }
}
