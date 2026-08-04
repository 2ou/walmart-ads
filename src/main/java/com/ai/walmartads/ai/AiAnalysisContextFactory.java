package com.ai.walmartads.ai;

import com.ai.walmartads.analytics.application.DashboardDataService;
import com.ai.walmartads.config.KieAiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiAnalysisContextFactory {

    private final DashboardDataService dashboardDataService;
    private final KieAiProperties properties;
    private final ObjectMapper objectMapper;

    public AiAnalysisContextFactory(
            DashboardDataService dashboardDataService,
            KieAiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.dashboardDataService = dashboardDataService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiContext create(int days) {
        return create(days, null, null);
    }

    public AiContext create(int days, LocalDate requestedStart, LocalDate requestedEnd) {
        if ((requestedStart == null) != (requestedEnd == null)) {
            throw new IllegalArgumentException("自定义时间范围必须同时提供开始日期和结束日期。");
        }
        DashboardDataService.DashboardPayload payload = dashboardDataService.load();
        LocalDate latestDate = payload.meta().latestDate();
        LocalDate startDate = requestedStart == null ? latestDate.minusDays(days - 1L) : requestedStart;
        LocalDate endDate = requestedEnd == null ? latestDate : requestedEnd;
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期。");
        }
        if (startDate.isBefore(payload.meta().firstDate()) || endDate.isAfter(latestDate)) {
            throw new IllegalArgumentException(
                    "AI 分析日期必须在 " + payload.meta().firstDate() + " 至 " + latestDate + " 之间。"
            );
        }
        List<DashboardDataService.DailyRow> daily = payload.daily().stream()
                .filter(row -> !row.date().isBefore(startDate) && !row.date().isAfter(endDate))
                .toList();
        if (daily.isEmpty() && payload.campaignDaily().isEmpty()) {
            throw new IllegalArgumentException("当前没有可供 AI 分析的广告或销量数据");
        }

        DashboardDataService.Defaults defaults = payload.defaults();
        List<DashboardDataService.Recommendation> recommendations = dashboardDataService.recommendations(
                        startDate,
                        endDate,
                        defaults.targetRoas(),
                        defaults.floorRoas(),
                        defaults.minClicks(),
                        defaults.minSpend()
                ).stream()
                .limit(Math.max(1, properties.maxCampaigns()))
                .toList();
        List<Map<String, Object>> topSkus = topSkus(payload.skuDaily(), startDate, endDate);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("dataAsOf", endDate);
        context.put("periodStart", startDate);
        context.put("periodEnd", endDate);
        context.put("periodDays", days);
        context.put("currency", "USD");
        context.put("totals", totals(daily));
        context.put("ruleThresholds", defaults);
        context.put("ruleRecommendations", recommendations);
        context.put("topSkus", topSkus);
        context.put("dailyTrend", daily);
        context.put("analysisConstraints", List.of(
                "规则结果优先，AI 只做跨维度解释和建议排序",
                "不执行广告修改",
                "样本不足时保持观察",
                "建议必须引用输入数据作为依据"
        ));

        try {
            return new AiContext(startDate, endDate, objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法生成 AI 分析上下文", exception);
        }
    }

    private List<Map<String, Object>> topSkus(
            List<DashboardDataService.SkuDailyRow> rows,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Map<String, MutableSku> grouped = new LinkedHashMap<>();
        rows.stream()
                .filter(row -> !row.date().isBefore(startDate) && !row.date().isAfter(endDate))
                .forEach(row -> grouped.computeIfAbsent(row.sku(), MutableSku::new).add(row));
        return grouped.values().stream()
                .sorted(Comparator.comparingDouble(MutableSku::revenue).reversed())
                .limit(Math.max(1, properties.maxSkus()))
                .map(MutableSku::toMap)
                .toList();
    }

    private static Map<String, Object> totals(List<DashboardDataService.DailyRow> daily) {
        double orders = daily.stream().mapToDouble(DashboardDataService.DailyRow::orders).sum();
        double revenue = daily.stream().mapToDouble(DashboardDataService.DailyRow::revenue).sum();
        double spend = daily.stream().mapToDouble(DashboardDataService.DailyRow::spend).sum();
        double adOrders = daily.stream().mapToDouble(DashboardDataService.DailyRow::adOrders).sum();
        double adSales = daily.stream().mapToDouble(DashboardDataService.DailyRow::adSales).sum();
        double clicks = daily.stream().mapToDouble(DashboardDataService.DailyRow::clicks).sum();
        double impressions = daily.stream().mapToDouble(DashboardDataService.DailyRow::impressions).sum();
        return Map.of(
                "orders", orders,
                "revenue", revenue,
                "adSpend", spend,
                "adOrders", adOrders,
                "naturalOrders", Math.max(0, orders - adOrders),
                "adSales", adSales,
                "roas", spend == 0 ? 0 : adSales / spend,
                "cpc", clicks == 0 ? 0 : spend / clicks,
                "ctr", impressions == 0 ? 0 : clicks / impressions
        );
    }

    public record AiContext(LocalDate startDate, LocalDate dataAsOf, String metricsJson) {
    }

    private static final class MutableSku {
        private final String sku;
        private double orders;
        private double revenue;
        private double impressions;
        private double clicks;
        private double spend;
        private double adOrders;
        private double adSales;

        private MutableSku(String sku) {
            this.sku = sku;
        }

        private void add(DashboardDataService.SkuDailyRow row) {
            orders += row.orders();
            revenue += row.revenue();
            impressions += row.impressions();
            clicks += row.clicks();
            spend += row.spend();
            adOrders += row.adOrders();
            adSales += row.adSales();
        }

        private double revenue() {
            return revenue;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sku", sku);
            result.put("orders", orders);
            result.put("revenue", revenue);
            result.put("impressions", impressions);
            result.put("clicks", clicks);
            result.put("adSpend", spend);
            result.put("adOrders", adOrders);
            result.put("adSales", adSales);
            result.put("roas", spend == 0 ? 0 : adSales / spend);
            result.put("cpc", clicks == 0 ? 0 : spend / clicks);
            return result;
        }
    }
}
