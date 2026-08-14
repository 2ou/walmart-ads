package com.ai.walmartads.analytics.application;

import com.ai.walmartads.analytics.application.DashboardDataService.CampaignDailyRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 广告活动决策：把「判定树」这一规则引擎保留在后端，阈值来自数据库配置。
 *
 * 设计取舍：
 * - 复用 DashboardDataService.campaignDaily() 的原始日行，按 (活动, 广告类型) 在窗口内聚合，
 *   避免再写一份 SQL，也保证与驾驶舱口径一致。
 * - 阈值按广告类型（自动广告 / 手动广告）分别配置；类型用中文主键，零映射。
 * - 判定逻辑写死在此处（浅配置）：只暴露 4 个阈值，结构稳定、易读、易审计。
 */
@Service
public class CampaignDecisionService {

    private final DashboardDataService dashboardDataService;
    private final CampaignDecisionConfigRepository configRepository;

    public CampaignDecisionService(
            DashboardDataService dashboardDataService,
            CampaignDecisionConfigRepository configRepository
    ) {
        this.dashboardDataService = dashboardDataService;
        this.configRepository = configRepository;
    }

    public List<CampaignDecisionConfig> getConfigs() {
        return configRepository.findAll();
    }

    public CampaignDecisionConfig saveConfig(CampaignDecisionConfig config) {
        return configRepository.save(config);
    }

    public DecisionAnalysis classify(int periodDays) {
        List<CampaignDailyRow> all = dashboardDataService.campaignDaily();
        LocalDate latest = all.stream()
                .map(CampaignDailyRow::date)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now());
        LocalDate start = latest.minusDays(Math.max(periodDays, 1) - 1L);

        Map<String, CampaignDecisionConfig> configByType = new LinkedHashMap<>();
        configRepository.findAll().forEach(cfg -> configByType.put(cfg.getCampaignType(), cfg));

        Map<String, MutableCampaign> groups = new LinkedHashMap<>();
        all.stream()
                .filter(row -> !row.date().isBefore(start) && !row.date().isAfter(latest))
                .forEach(row -> groups.computeIfAbsent(row.campaign() + "\u0000" + row.type(), ignored -> new MutableCampaign(row)).add(row));

        List<CampaignDecisionRow> rows = groups.values().stream()
                .map(group -> {
                    CampaignDecisionConfig cfg = configByType.getOrDefault(group.type, fallbackConfig(group.type));
                    String decision = decide(group, cfg);
                    return new CampaignDecisionRow(
                            group.campaign,
                            group.campaignId,
                            group.type,
                            group.sku,
                            round(group.spend),
                            round(group.adSales),
                            round(group.adOrders),
                            round(group.clicks),
                            group.spend == 0 ? 0 : round(group.adSales / group.spend),
                            group.clicks == 0 ? 0 : round(group.spend / group.clicks),
                            decision
                    );
                })
                .sorted(Comparator.comparing(CampaignDecisionRow::decision).thenComparing(CampaignDecisionRow::campaign))
                .toList();

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("scale", 0);
        summary.put("reduce", 0);
        summary.put("optimize", 0);
        summary.put("collect", 0);
        rows.forEach(row -> summary.merge(row.decision(), 1, Integer::sum));

        return new DecisionAnalysis(periodDays, start, latest, rows, summary);
    }

    private String decide(MutableCampaign group, CampaignDecisionConfig cfg) {
        double roas = group.spend == 0 ? 0 : group.adSales / group.spend;
        boolean enough = group.clicks >= cfg.getMinClicks() && group.spend >= cfg.getMinSpend();
        if (!enough) {
            return "collect";
        }
        if (roas >= cfg.getTargetRoas() && group.adOrders >= 3) {
            return "scale";
        }
        if (roas < cfg.getFloorRoas() || group.adOrders == 0) {
            return "reduce";
        }
        return "optimize";
    }

    private CampaignDecisionConfig fallbackConfig(String type) {
        return new CampaignDecisionConfig(type, 20, 10.0, 3.0, 1.5);
    }

    private static double round(double value) {
        return Math.round(value * 1e6) / 1e6;
    }

    public record CampaignDecisionRow(
            String campaign,
            String campaignId,
            String type,
            String sku,
            double spend,
            double adSales,
            double adOrders,
            double clicks,
            double roas,
            double cpc,
            String decision
    ) {
    }

    public record DecisionAnalysis(
            int periodDays,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            List<CampaignDecisionRow> rows,
            Map<String, Integer> summary
    ) {
    }

    private static final class MutableCampaign {
        private final String campaign;
        private final String campaignId;
        private final String type;
        private final String sku;
        private double spend;
        private double adSales;
        private double adOrders;
        private double clicks;

        private MutableCampaign(CampaignDailyRow row) {
            this.campaign = row.campaign();
            this.campaignId = row.campaignId();
            this.type = row.type();
            this.sku = row.sku();
        }

        private void add(CampaignDailyRow row) {
            spend += row.spend();
            adSales += row.adSales();
            adOrders += row.adOrders();
            clicks += row.clicks();
        }
    }
}
