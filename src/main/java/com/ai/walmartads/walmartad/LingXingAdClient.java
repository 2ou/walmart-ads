package com.ai.walmartads.walmartad;

import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.walmartlisting.LingXingClient;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.LingXingMcpCoordinator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fetches the complete 18-tool Walmart advertising matrix for one report day. */
@Component
public class LingXingAdClient {

    private static final Logger log = LoggerFactory.getLogger(LingXingAdClient.class);
    public static final int ATTRIBUTION_DAYS = 14;
    private static final List<String> SP_TYPES = List.of(
            "sponsoredProducts-manual", "sponsoredProducts-auto");
    private static final List<String> SB_TYPES = List.of("sba");
    private static final List<String> SV_TYPES = List.of("video");

    private static final List<ToolSpec> TOOLS = List.of(
            report("get_walmart_sp_campaign_report", "SP", ReportLevel.CAMPAIGN, true, SP_TYPES, "campaignId", true, false),
            report("get_walmart_sp_ad_group_report", "SP", ReportLevel.AD_GROUP, true, SP_TYPES, "adGroupId", true, false),
            report("get_walmart_sp_ad_item_report", "SP", ReportLevel.AD_ITEM, false, SP_TYPES, "itemId", false, false),
            report("get_walmart_sp_keyword_report", "SP", ReportLevel.KEYWORD, false, SP_TYPES, "keywordName", false, false),
            report("get_walmart_sb_campaign_report", "SB", ReportLevel.CAMPAIGN, false, SB_TYPES, "campaignId", false, false),
            report("get_walmart_sb_ad_group_report", "SB", ReportLevel.AD_GROUP, false, SB_TYPES, "adGroupId", false, false),
            report("get_walmart_sb_ad_item_report", "SB", ReportLevel.AD_ITEM, false, SB_TYPES, "itemId", false, false),
            report("get_walmart_sb_keyword_report", "SB", ReportLevel.KEYWORD, false, SB_TYPES, "keywordName", false, false),
            report("get_walmart_sv_campaign_report", "SV", ReportLevel.CAMPAIGN, false, SV_TYPES, "campaignId", true, false),
            report("get_walmart_sv_ad_group_report", "SV", ReportLevel.AD_GROUP, false, SV_TYPES, "adGroupId", false, true),
            report("get_walmart_sv_ad_item_report", "SV", ReportLevel.AD_ITEM, false, SV_TYPES, "itemId", false, false),
            report("get_walmart_sv_keyword_report", "SV", ReportLevel.KEYWORD, false, SV_TYPES, "keywordName", false, false),
            slice("platform_page_type_splist", "SP", SliceType.PAGE_TYPE, true, SP_TYPES, "pageType", false),
            slice("platform_platform_sp_list", "SP", SliceType.PLATFORM, false, SP_TYPES, "platform", true),
            slice("platform_platform_sb_list", "SB", SliceType.PLATFORM, false, SB_TYPES, "platform", true),
            slice("platform_platform_sv_list", "SV", SliceType.PLATFORM, false, SV_TYPES, "platform", true),
            slice("platform_type_sb_list", "SB", SliceType.PAGE_TYPE, false, SB_TYPES, "pageType", true),
            slice("platform_type_sv_list", "SV", SliceType.PAGE_TYPE, false, SV_TYPES, "pageType", true)
    );

    private final LingXingProperties properties;
    private final ObjectMapper objectMapper;
    private final WorkspaceLingXingConfigService workspaceConfig;
    private final LingXingMcpCoordinator mcpCoordinator;

    public LingXingAdClient(
            LingXingProperties properties,
            ObjectMapper objectMapper,
            WorkspaceLingXingConfigService workspaceConfig,
            LingXingMcpCoordinator mcpCoordinator
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.workspaceConfig = workspaceConfig;
        this.mcpCoordinator = mcpCoordinator;
    }

    public boolean configured() {
        WorkspaceLingXingConfigService.Config config = workspaceConfig.current();
        return config.configured() && !config.advertiserIds().isEmpty();
    }

    public FetchedDay fetchDay(LocalDate date) {
        if (!configured()) {
            throw new IllegalStateException("LingXing MCP is not configured");
        }
        WorkspaceLingXingConfigService.Config config = workspaceConfig.current();
        LingXingClient client = new LingXingClient(config.baseUrl(), config.apiKey(), config.pageSize(),
                10, 180, objectMapper, config.advertiserIds(), "ADS", mcpCoordinator);
        List<FetchedTool> results = new ArrayList<>(TOOLS.size());
        for (ToolSpec spec : TOOLS) {
            List<Map<String, Object>> rows = fetchPaged(client, config, spec, date);
            results.add(new FetchedTool(spec, rows));
        }
        return new FetchedDay(date, results);
    }

    private List<Map<String, Object>> fetchPaged(
            LingXingClient client,
            WorkspaceLingXingConfigService.Config config,
            ToolSpec spec,
            LocalDate date
    ) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("advertiserIds", spec.stringAdvertiserIds()
                ? config.advertiserIds().stream().map(String::valueOf).toList()
                : config.advertiserIds());
        base.put("campaignType", spec.campaignTypes());
        base.put("startDate", date.toString());
        base.put("endDate", date.toString());
        base.put("day", ATTRIBUTION_DAYS);
        if (spec.operationSource()) {
            base.put("operationSourceType", "gateway");
        }
        if (spec.dateKey()) {
            base.put("dateKey", "day");
        }
        if (spec.pagingFlag()) {
            base.put("paging", true);
        }

        int pageSize = Math.max(1, Math.min(config.pageSize(), 200));
        List<Map<String, Object>> all = new ArrayList<>();
        int pageNum = 1;
        int expectedTotal = -1;
        while (pageNum <= 1000) {
            Map<String, Object> params = new LinkedHashMap<>(base);
            params.put("pageNum", pageNum);
            params.put("pageSize", pageSize);
            JsonNode data = callWithRetry(client, spec, date, pageNum, params);
            JsonNode listNode = data.path("list");
            if (!listNode.isArray() || listNode.isEmpty()) {
                if (expectedTotal >= 0 && all.size() < expectedTotal) {
                    throw incompletePagination(spec, date, pageNum, all.size(), expectedTotal);
                }
                break;
            }
            List<Map<String, Object>> page = objectMapper.convertValue(
                    listNode, new TypeReference<List<Map<String, Object>>>() { });
            int added = 0;
            for (Map<String, Object> row : page) {
                if (isBlank(row.get(spec.identityField()))) {
                    continue;
                }
                all.add(row);
                added++;
            }
            int total = data.path("total").asInt(data.path("count").asInt(-1));
            // Report endpoints expose an entity-row total. Slice endpoints use total/count for
            // aggregate concepts that do not equal the returned dimension rows, so their page
            // boundary must continue to be determined by the actual filtered page size.
            if (spec.reportLevel() != null && total >= 0) {
                expectedTotal = Math.max(expectedTotal, total);
            }
            if ((expectedTotal >= 0 && all.size() >= expectedTotal)
                    || (expectedTotal < 0 && added < pageSize)) {
                break;
            }
            pageNum++;
        }
        if (pageNum > 1000) {
            throw new IllegalStateException("LingXing pagination exceeded 1000 pages: " + spec.toolId());
        }
        if (expectedTotal >= 0 && all.size() < expectedTotal) {
            throw incompletePagination(spec, date, pageNum, all.size(), expectedTotal);
        }
        log.info("LingXing ad tool completed date={}, toolId={}, rows={}", date, spec.toolId(), all.size());
        return all;
    }

    private static IllegalStateException incompletePagination(
            ToolSpec spec,
            LocalDate date,
            int pageNum,
            int fetched,
            int expected
    ) {
        return new IllegalStateException("LingXing pagination incomplete: tool=" + spec.toolId()
                + ", date=" + date + ", page=" + pageNum
                + ", fetched=" + fetched + ", expected=" + expected);
    }

    private JsonNode callWithRetry(
            LingXingClient client,
            ToolSpec spec,
            LocalDate date,
            int pageNum,
            Map<String, Object> params
    ) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return client.callTool(spec.toolId(), params);
            } catch (RuntimeException exception) {
                last = exception;
                if (exception instanceof LingXingClient.RateLimitedException) {
                    throw exception;
                }
                if (attempt == 3) {
                    break;
                }
                long delayMs = attempt * 2_000L;
                log.warn("LingXing ad request will retry date={}, toolId={}, page={}, attempt={}/3, delayMs={}, error={}",
                        date, spec.toolId(), pageNum, attempt, delayMs, exception.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("LingXing ad retry was interrupted", interrupted);
                }
            }
        }
        throw last == null ? new IllegalStateException("LingXing ad request failed") : last;
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static ToolSpec report(
            String toolId, String adType, ReportLevel level, boolean stringIds,
            List<String> campaignTypes, String identityField, boolean operationSource, boolean dateKey) {
        return new ToolSpec(toolId, adType, level, null, stringIds, campaignTypes,
                identityField, operationSource, dateKey, true);
    }

    private static ToolSpec slice(
            String toolId, String adType, SliceType type, boolean stringIds,
            List<String> campaignTypes, String identityField, boolean pagingFlag) {
        return new ToolSpec(toolId, adType, null, type, stringIds, campaignTypes,
                identityField, false, false, pagingFlag);
    }

    public enum ReportLevel { CAMPAIGN, AD_GROUP, AD_ITEM, KEYWORD }
    public enum SliceType { PAGE_TYPE, PLATFORM }

    public record ToolSpec(
            String toolId,
            String adType,
            ReportLevel reportLevel,
            SliceType sliceType,
            boolean stringAdvertiserIds,
            List<String> campaignTypes,
            String identityField,
            boolean operationSource,
            boolean dateKey,
            boolean pagingFlag
    ) { }

    public record FetchedTool(ToolSpec spec, List<Map<String, Object>> rows) { }
    public record FetchedDay(LocalDate date, List<FetchedTool> tools) { }
}
