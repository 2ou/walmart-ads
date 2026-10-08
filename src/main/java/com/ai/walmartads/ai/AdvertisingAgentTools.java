package com.ai.walmartads.ai;

import com.ai.walmartads.analytics.application.CampaignStyleDiagnosisService;
import com.ai.walmartads.analytics.application.ManualKeywordAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdvertisingAgentTools {

    public static final String OVERVIEW = "query_account_ad_overview";
    public static final String DIAGNOSIS = "query_campaign_diagnosis";
    public static final String KEYWORDS = "query_manual_keyword_performance";
    public static final String HISTORY = "query_historical_ads";
    public static final String FRESHNESS = "query_data_freshness";

    private final AiAnalysisContextFactory contextFactory;
    private final CampaignStyleDiagnosisService diagnosisService;
    private final ManualKeywordAnalysisService keywordService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdvertisingAgentTools(
            AiAnalysisContextFactory contextFactory,
            CampaignStyleDiagnosisService diagnosisService,
            ManualKeywordAnalysisService keywordService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.contextFactory = contextFactory;
        this.diagnosisService = diagnosisService;
        this.keywordService = keywordService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ToolSpecification specification(String toolCode) {
        return switch (toolCode) {
            case OVERVIEW -> tool(OVERVIEW, "读取所选日期范围的广告经营总览、规则建议、SKU 与趋势。",
                    commonDateSchema());
            case DIAGNOSIS -> tool(DIAGNOSIS, "读取款号广告诊断；可指定 parentSku，未指定时返回整体诊断摘要。",
                    commonDateSchema().toBuilder()
                            .addStringProperty("parentSku", "可选款号，例如 PNK0079")
                            .build());
            case KEYWORDS -> tool(KEYWORDS, "读取手动广告关键词表现；支持款号、广告类型和返回条数筛选。",
                    commonDateSchema().toBuilder()
                            .addStringProperty("parentSku", "可选款号")
                            .addEnumProperty("adType", List.of("ALL", "SP", "SB", "SV"), "广告类型")
                            .addIntegerProperty("limit", "返回关键词上限，1-80")
                            .build());
            case HISTORY -> tool(HISTORY, "读取指定款号的历史广告生命周期汇总和广告明细。",
                    JsonObjectSchema.builder()
                            .addStringProperty("parentSku", "必填款号，例如 PNK0079")
                            .required("parentSku")
                            .additionalProperties(false)
                            .build());
            case FRESHNESS -> tool(FRESHNESS, "读取领星广告报表各层级的新鲜度和最近同步任务状态。",
                    JsonObjectSchema.builder().additionalProperties(false).build());
            default -> throw new IllegalArgumentException("未知 Agent 工具: " + toolCode);
        };
    }

    public String execute(String toolCode, String rawArguments, Scope scope) {
        JsonNode args = parseArguments(rawArguments);
        Object result = switch (toolCode) {
            case OVERVIEW -> overview(scope);
            case DIAGNOSIS -> diagnosis(scope, text(args, "parentSku"));
            case KEYWORDS -> keywords(scope, args);
            case HISTORY -> history(scope, text(args, "parentSku"));
            case FRESHNESS -> freshness();
            default -> throw new IllegalArgumentException("未知 Agent 工具: " + toolCode);
        };
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化 Agent 工具结果", exception);
        }
    }

    private Object overview(Scope scope) {
        int days = Math.toIntExact(ChronoUnit.DAYS.between(scope.startDate(), scope.endDate()) + 1);
        AiAnalysisContextFactory.AiContext context = contextFactory.create(days, scope.startDate(), scope.endDate());
        try {
            return objectMapper.readTree(context.metricsJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("广告经营总览包含无效 JSON", exception);
        }
    }

    private Object diagnosis(Scope scope, String requestedParentSku) {
        String parentSku = effectiveParentSku(scope.parentSku(), requestedParentSku);
        if (StringUtils.hasText(parentSku)) {
            CampaignStyleDiagnosisService.StyleDiagnosis style =
                    diagnosisService.styleDiagnosis(scope.startDate(), scope.endDate(), parentSku);
            return Map.of(
                    "scope", scopeMap(scope, parentSku),
                    "style", compactStyle(style)
            );
        }
        CampaignStyleDiagnosisService.DiagnosisResponse response =
                diagnosisService.diagnoseSummary(scope.startDate(), scope.endDate());
        List<Map<String, Object>> styles = response.styles().stream()
                .sorted(java.util.Comparator.comparingDouble(CampaignStyleDiagnosisService.StyleDiagnosis::spend).reversed())
                .limit(30)
                .map(this::compactStyle)
                .toList();
        return Map.of(
                "scope", scopeMap(scope, null),
                "meta", response.meta(),
                "summary", response.summary(),
                "priorityActions", response.priorityActions(),
                "methodology", response.methodology(),
                "topStylesBySpend", styles,
                "returnedStyleCount", styles.size(),
                "totalStyleCount", response.styles().size()
        );
    }

    private Map<String, Object> compactStyle(CampaignStyleDiagnosisService.StyleDiagnosis style) {
        List<Map<String, Object>> campaigns = style.campaigns().stream().limit(50).map(campaign -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("advertiserId", campaign.advertiserId());
            row.put("adType", campaign.adType());
            row.put("campaignId", campaign.campaignId());
            row.put("campaignName", campaign.campaignName());
            row.put("campaignType", campaign.campaignType());
            row.put("targetingType", campaign.targetingType());
            row.put("campaignStatus", campaign.campaignStatus());
            row.put("sourceCampaignStatus", campaign.sourceCampaignStatus());
            row.put("dailyBudget", campaign.dailyBudget());
            row.put("biddingStrategy", campaign.biddingStrategy());
            row.put("configuredItemCount", campaign.configuredItemCount());
            row.put("enabledItemCount", campaign.enabledItemCount());
            row.put("actualServingItemCount", campaign.actualServingItemCount());
            row.put("spend", campaign.spend());
            row.put("adSales", campaign.adSales());
            row.put("adOrders", campaign.adOrders());
            row.put("clicks", campaign.clicks());
            return row;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parentSku", style.parentSku());
        result.put("campaignCount", style.campaignCount());
        result.put("historicalCampaignCount", style.historicalCampaignCount());
        result.put("configuredItemCount", style.configuredItemCount());
        result.put("enabledItemCount", style.enabledItemCount());
        result.put("actualServingItemCount", style.actualServingItemCount());
        result.put("spend", style.spend());
        result.put("adSales", style.adSales());
        result.put("adOrders", style.adOrders());
        result.put("clicks", style.clicks());
        result.put("campaigns", campaigns);
        return result;
    }

    private Object keywords(Scope scope, JsonNode args) {
        String requestedAdType = text(args, "adType");
        String adType = StringUtils.hasText(scope.adType()) ? scope.adType() : requestedAdType;
        ManualKeywordAnalysisService.Analysis analysis =
                keywordService.analyze(scope.startDate(), scope.endDate(), adType);
        String parentSku = effectiveParentSku(scope.parentSku(), text(args, "parentSku"));
        int limit = Math.max(1, Math.min(args.path("limit").asInt(50), 80));
        List<ManualKeywordAnalysisService.KeywordRow> filtered = analysis.keywords().stream()
                .filter(row -> !StringUtils.hasText(parentSku) || parentSku.equalsIgnoreCase(row.parentSku()))
                .toList();
        List<ManualKeywordAnalysisService.KeywordRow> returned = filtered.stream().limit(limit).toList();
        long clicks = filtered.stream().mapToLong(ManualKeywordAnalysisService.KeywordRow::clicks).sum();
        long impressions = filtered.stream().mapToLong(ManualKeywordAnalysisService.KeywordRow::impressions).sum();
        double spend = filtered.stream().mapToDouble(ManualKeywordAnalysisService.KeywordRow::spend).sum();
        double sales = filtered.stream().mapToDouble(ManualKeywordAnalysisService.KeywordRow::sales).sum();
        double orders = filtered.stream().mapToDouble(ManualKeywordAnalysisService.KeywordRow::orders).sum();
        Map<String, Object> filteredSummary = new LinkedHashMap<>();
        filteredSummary.put("keywordCount", filtered.size());
        filteredSummary.put("impressions", impressions);
        filteredSummary.put("clicks", clicks);
        filteredSummary.put("spend", spend);
        filteredSummary.put("sales", sales);
        filteredSummary.put("orders", orders);
        filteredSummary.put("roas", spend == 0 ? 0 : sales / spend);
        filteredSummary.put("ctr", impressions == 0 ? 0 : (double) clicks / impressions);
        filteredSummary.put("cvr", clicks == 0 ? 0 : orders / clicks);
        return Map.of(
                "scope", scopeMap(scope, parentSku),
                "meta", analysis.meta(),
                "thresholds", analysis.thresholds(),
                "filteredSummary", filteredSummary,
                "matchTypesOverall", analysis.matchTypes(),
                "keywords", returned,
                "returnedKeywordCount", returned.size(),
                "filteredKeywordCount", filtered.size()
        );
    }

    private Object history(Scope scope, String requestedParentSku) {
        String parentSku = effectiveParentSku(scope.parentSku(), requestedParentSku);
        if (!StringUtils.hasText(parentSku)) {
            throw new IllegalArgumentException("历史广告分析必须提供 parentSku");
        }
        CampaignStyleDiagnosisService.HistoryResponse history = diagnosisService.history(parentSku);
        List<Map<String, Object>> campaigns = history.campaigns().stream().limit(50).map(campaign -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("advertiserId", campaign.advertiserId());
            row.put("adType", campaign.adType());
            row.put("campaignId", campaign.campaignId());
            row.put("campaignName", campaign.campaignName());
            row.put("campaignType", campaign.campaignType());
            row.put("targetingType", campaign.targetingType());
            row.put("campaignStatus", campaign.campaignStatus());
            row.put("sourceCampaignStatus", campaign.sourceCampaignStatus());
            row.put("dailyBudget", campaign.dailyBudget());
            row.put("biddingStrategy", campaign.biddingStrategy());
            row.put("firstDate", campaign.firstDate());
            row.put("lastDate", campaign.lastDate());
            row.put("metrics", campaign.metrics());
            row.put("itemCount", campaign.items().size());
            return row;
        }).toList();
        return Map.of(
                "scope", scopeMap(scope, parentSku),
                "parentSku", history.parentSku(),
                "generatedAt", history.generatedAt(),
                "summary", history.summary(),
                "campaigns", campaigns,
                "returnedCampaignCount", Math.min(history.campaigns().size(), 50),
                "totalCampaignCount", history.campaigns().size()
        );
    }

    private Object freshness() {
        List<Map<String, Object>> reportLevels = jdbcTemplate.queryForList("""
                select report_level as reportLevel, ad_type as adType,
                       min(report_date) as firstDate, max(report_date) as latestDate,
                       count(distinct report_date) as coveredDays,
                       count(*) as rowCount, max(synced_at) as lastSyncedAt
                  from wm_ads_lx_ad_report
                 group by report_level, ad_type
                 order by report_level, ad_type
                """);
        List<Map<String, Object>> syncRuns = jdbcTemplate.queryForList("""
                select id, started_at as startedAt, finished_at as finishedAt, status, source,
                       triggered_by as triggeredBy, window_start as windowStart, window_end as windowEnd,
                       completed_tool_count as completedToolCount, tool_count as toolCount,
                       report_rows as reportRows, slice_rows as sliceRows, duration_ms as durationMs,
                       error_message as errorMessage
                  from wm_ads_lx_ad_sync_log
                 order by started_at desc, id desc
                 limit 10
                """);
        return Map.of(
                "checkedAt", java.time.LocalDateTime.now(),
                "reportLevels", reportLevels,
                "recentSyncRuns", syncRuns
        );
    }

    private static JsonObjectSchema commonDateSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("startDate", "分析开始日期 YYYY-MM-DD；系统会强制使用用户选定范围")
                .addStringProperty("endDate", "分析结束日期 YYYY-MM-DD；系统会强制使用用户选定范围")
                .additionalProperties(false)
                .build();
    }

    private static ToolSpecification tool(String name, String description, JsonObjectSchema parameters) {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .strict(false)
                .build();
    }

    private JsonNode parseArguments(String rawArguments) {
        if (!StringUtils.hasText(rawArguments)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawArguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 工具参数不是有效 JSON", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String effectiveParentSku(String lockedParentSku, String requestedParentSku) {
        String value = StringUtils.hasText(lockedParentSku) ? lockedParentSku : requestedParentSku;
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static Map<String, Object> scopeMap(Scope scope, String parentSku) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", scope.startDate());
        result.put("endDate", scope.endDate());
        result.put("parentSku", parentSku);
        result.put("adType", scope.adType());
        return result;
    }

    public record Scope(LocalDate startDate, LocalDate endDate, String parentSku, String adType) {
        public Scope {
            if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("Agent 分析日期范围无效");
            }
            if (ChronoUnit.DAYS.between(startDate, endDate) > 3650) {
                throw new IllegalArgumentException("Agent 分析日期范围不能超过 10 年");
            }
            parentSku = StringUtils.hasText(parentSku) ? parentSku.trim().toUpperCase(Locale.ROOT) : null;
            adType = StringUtils.hasText(adType) ? adType.trim().toUpperCase(Locale.ROOT) : null;
            if (adType != null && !List.of("ALL", "SP", "SB", "SV").contains(adType)) {
                throw new IllegalArgumentException("不支持的广告类型: " + adType);
            }
        }
    }
}
