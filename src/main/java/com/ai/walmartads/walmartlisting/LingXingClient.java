package com.ai.walmartads.walmartlisting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 领星 MCP 网关客户端：无状态 JSON-RPC，业务工具必须经 {@code tools/call → action(toolId)} 包一层。
 * 双层信封：HTTP body → result.content[0].text（JSON 字符串）→ data.data.{count,list}。
 *
 * <p>在线商品同步走 {@link #fetchAllListings(String)}；广告三块（placement/keyword/campaign）走
 * {@link #fetchPlacement()} / {@link #fetchKeywords()} / {@link #fetchCampaigns()}。
 * 其余业务工具可走通用入口 {@link #callTool(String, Map)}，返回信封内层 {@code data.data}。
 */
public class LingXingClient {

    private static final Logger log = LoggerFactory.getLogger(LingXingClient.class);
    private static final String LISTING_TOOL_ID = "platform_listing_walmart_list";

    /** 沃尔玛广告报告工具强制必填 advertiserIds，且 SP 系各工具类型不一致：
     *  page_type 是 string 数组，keyword/campaign 是 integer 数组，传错会 422。 */
    private static final List<String> AD_CAMPAIGN_TYPES = List.of(
            "sponsoredProducts-auto", "sponsoredProducts-manual");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final int pageSize;
    private final List<Long> advertiserIds;

    public LingXingClient(
            String baseUrl,
            String apiKey,
            int pageSize,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            ObjectMapper objectMapper,
            List<Long> advertiserIds
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.pageSize = pageSize;
        this.objectMapper = objectMapper;
        this.advertiserIds = advertiserIds == null ? List.of() : advertiserIds;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutSeconds * 1000);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Mcp-Key", apiKey)
                .defaultHeader("Accept", "application/json, text/event-stream")
                .build();
    }

    public List<WalmartListingDto> fetchAllListings(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("Walmart 店铺 ID 不能为空");
        }
        List<WalmartListingDto> all = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
            JsonNode data = callTool(LISTING_TOOL_ID, Map.of(
                    "length", pageSize,
                    "offset", offset,
                    "store_ids", List.of(storeId)
            ));
            JsonNode listNode = data.path("list");
            List<WalmartListingDto> page = objectMapper.convertValue(
                    listNode, new TypeReference<List<WalmartListingDto>>() {
                    });
            if (page == null || page.isEmpty()) {
                break;
            }
            total = data.path("count").asInt();
            for (WalmartListingDto row : page) {
                if (row.storeId == null || !storeId.equals(row.storeId)) {
                    throw new IllegalStateException("领星 Listing 返回了不属于当前店铺的数据");
                }
                all.add(row);
            }
            offset += page.size();
            if (all.size() >= total) {
                break;
            }
        }
        log.info("领星 MCP 沃尔玛在线商品拉取完成：total={}, fetched={}", total, all.size());
        return all;
    }

    /**
     * 广告 - 页面类型切片（Carousel/Search In-grid/Buybox/Homepage 等）。
     * 维度：每行一个 (campaign × pageType)；首行是 pageType 为空的合计行，需跳过。
     * advertiserIds 为 **string** 数组。
     */
    public List<Map<String, Object>> fetchPlacement(int windowDays, String endDate, String startDate) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("advertiserIds", advertiserIds.stream().map(String::valueOf).toList());
        base.put("campaignType", AD_CAMPAIGN_TYPES);
        base.put("startDate", startDate);
        base.put("endDate", endDate);
        base.put("day", 14);
        return fetchPagedList("platform_page_type_splist", base,
                row -> isBlank(row.get("pageType")));
    }

    /**
     * 广告 - 真实搜索词（keywordName 是真实词，且带 targetingType 真值）。
     * 首行是 keywordName 为空的合计行，需跳过。advertiserIds 为 **integer** 数组。
     */
    public List<Map<String, Object>> fetchKeywords(int windowDays, String endDate, String startDate) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("advertiserIds", advertiserIds);
        base.put("campaignType", AD_CAMPAIGN_TYPES);
        base.put("startDate", startDate);
        base.put("endDate", endDate);
        base.put("day", 14);
        base.put("paging", true);
        return fetchPagedList("get_walmart_sp_keyword_report", base,
                row -> isBlank(row.get("keywordName")));
    }

    /**
     * 广告 - campaign 元数据（campaignId/name/status/platform + 窗口指标）。
     * 首行是 campaignId 为空的合计行，需跳过。advertiserIds 为 **integer** 数组。
     */
    public List<Map<String, Object>> fetchCampaigns(int windowDays, String endDate, String startDate) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("advertiserIds", advertiserIds);
        base.put("campaignType", AD_CAMPAIGN_TYPES);
        base.put("startDate", startDate);
        base.put("endDate", endDate);
        base.put("day", 14);
        base.put("paging", true);
        return fetchPagedList("platform_platform_sp_list", base,
                row -> isBlank(row.get("campaignId")));
    }

    /**
     * 通用分页拉取：循环 pageNum 直到 list 为空或不足一页；跳过合计行（skip 谓词命中）。
     * 返回原始 Map 行列表，供 Service 层按字段名解析（领星返回字段类型不统一，不在此强转）。
     */
    private List<Map<String, Object>> fetchPagedList(
            String toolId, Map<String, Object> baseParams, Predicate<Map<String, Object>> skipRow) {
        List<Map<String, Object>> all = new ArrayList<>();
        int pageNum = 1;
        while (true) {
            Map<String, Object> params = new LinkedHashMap<>(baseParams);
            params.put("pageNum", pageNum);
            params.put("pageSize", pageSize);
            JsonNode data = callTool(toolId, params);
            JsonNode listNode = data.path("list");
            if (listNode == null || listNode.isMissingNode() || !listNode.isArray() || listNode.isEmpty()) {
                break;
            }
            List<Map<String, Object>> page = objectMapper.convertValue(
                    listNode, new TypeReference<List<Map<String, Object>>>() {
                    });
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : page) {
                if (skipRow.test(row)) {
                    continue;
                }
                all.add(row);
            }
            if (page.size() < pageSize) {
                break;
            }
            pageNum++;
        }
        log.info("领星 MCP 广告拉取完成 toolId={}, rows={}", toolId, all.size());
        return all;
    }

    /**
     * 通用业务工具调用：{@code tools/call → action(toolId)}，返回信封内层 {@code data.data}。
     *
     * @param toolId 领星工具 ID，如 {@code platform_v2_page_list}
     * @param params 工具入参；空调用传 {@code Map.of()}
     * @return {@code data.data} 节点；调用失败抛 {@link IllegalStateException}
     */
    public JsonNode callTool(String toolId, Map<String, Object> params) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("toolId", toolId);
        arguments.put("params", params == null ? Map.of() : params);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", "action", "arguments", arguments));
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("领星 MCP 请求体序列化失败：" + exception.getMessage(), exception);
        }
        return extractData(post(body, toolId), toolId);
    }

    private String post(String body, String toolId) {
        try {
            return restClient.post()
                    .uri(baseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.valueOf("text/event-stream"))
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception exception) {
            log.error("领星 MCP 调用失败 toolId={}: {}", toolId, exception.getMessage());
            throw new IllegalStateException("领星 MCP 调用失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 拆双层信封并取 {@code data.data}。
     *
     * <p>领星把工具的业务结果再套一层：{@code data.data.{count,list}}（列表工具）或
     * {@code data.data.{total,list}}（keyword 工具）。调用方只关心最内层。
     */
    private JsonNode extractData(String response, String toolId) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("领星 MCP 返回为空 toolId=" + toolId);
        }
        String json = response.trim();
        int firstBrace = json.indexOf('{');
        int lastBrace = json.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            json = json.substring(firstBrace, lastBrace + 1);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode content = root.path("result").path("content");
            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                if (text != null && !text.isBlank()) {
                    JsonNode envelope = objectMapper.readTree(text);
                    JsonNode data = envelope.path("data");
                    if (!data.path("success").asBoolean(true)) {
                        String message = data.path("msg").asText("未知错误");
                        throw new IllegalStateException(
                                "领星 MCP 业务失败 toolId=" + toolId + "：" + message);
                    }
                    return data.path("data");
                }
            }
            throw new IllegalStateException("领星 MCP 返回结构异常：缺少 result.content[0].text");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("领星 MCP 返回解析失败：" + exception.getMessage(), exception);
        }
    }

    private static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        return false;
    }
}
