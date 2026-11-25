package com.ai.walmartads.config;

import com.ai.walmartads.common.UsBusinessDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 领星 MCP 配置（沃尔玛在线商品每小时同步 + 广告三块同步的数据源）。
 * 店铺连接配置由工作区持久化，部署属性可用于首次绑定。
 */
@ConfigurationProperties(prefix = "walmart.ads.lingxing")
public record LingXingProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String cron,
        int pageSize,
        String storeId,
        int orderWindowDays,
        /** 沃尔玛广告账号 ID 列表（领星广告报告工具强制必填）。 */
        List<Long> advertiserIds,
        /** 广告每小时滚动覆盖天数，至少为今天加过去 15 天。 */
        int adWindowDays,
        /** 首次历史回填起始日。 */
        LocalDate adBackfillStart,
        /**
         * 店铺评价同步的独立调度。默认每日 08:30 —— 评价更新频率远低于商品/订单/广告，
         * 无需挂在小时级 cron 上。
         */
        String commentCron,
        /** 评价同步滚动窗口天数。默认 31 天，覆盖领星延迟发布与买家事后编辑。 */
        int commentWindowDays,
        /** 评价首次历史回填起始日。评价接口无窗口上限，一次跑完。 */
        LocalDate commentBackfillStart,
        /** 评价全历史对账调度。默认每周日 09:10，修复超过滚动窗口才出现的迟到评价。 */
        String commentReconcileCron
) {

    public boolean configured() {
        return enabled && StringUtils.hasText(apiKey);
    }

    public String resolvedBaseUrl() {
        return StringUtils.hasText(baseUrl)
                ? baseUrl
                : "https://openmcp.lingxing.com/mcp-servers/lingxing-mcp";
    }

    /**
     * 调度表达式，**在线商品同步与销量/订单同步共用同一个 key**：两者同源、同频，
     * 改这一处即同时生效，不存在两套节奏对不齐的可能。默认每小时整点。
     */
    public String resolvedCron() {
        return StringUtils.hasText(cron) ? cron : "0 0 * * * *";
    }

    public int resolvedPageSize() {
        return pageSize > 0 ? pageSize : 200;
    }

    public String resolvedStoreId() {
        return StringUtils.hasText(storeId) ? storeId.strip() : null;
    }

    /**
     * 每次同步回溯的天数（滚动窗口）。默认 3 天（T-2 ~ T）。
     *
     * <p>小时级调度下 3 天已足够：当日数据滚动修正 + 前一日补正，再多只是重复拉取。
     * 停机导致的窗口缺口不靠加大窗口兜底，而是由 {@code syncNow} 的「补窗」逻辑
     * 依据上次成功窗口自动向前扩展 —— 因为 PO_Data 已停用，漏掉的日期没有第二来源可补。
     */
    public int resolvedOrderWindowDays() {
        if (orderWindowDays <= 0) {
            return 3;
        }
        return Math.min(orderWindowDays, 90);
    }

    /** 首次绑定时可预填部署环境显式提供的广告账号。 */
    public List<Long> resolvedAdvertiserIds() {
        return advertiserIds == null ? List.of() : List.copyOf(advertiserIds);
    }

    /** 广告同步滚动窗口；至少覆盖完整 14 天归因期并多回看两天。 */
    public int resolvedAdWindowDays() {
        int configuredDays = adWindowDays <= 0 ? 16 : adWindowDays;
        return Math.max(16, Math.min(configuredDays, 31));
    }

    public LocalDate resolvedAdBackfillStart() {
        return adBackfillStart == null ? UsBusinessDate.usToday().minusDays(resolvedAdWindowDays() - 1L) : adBackfillStart;
    }

    /**
     * 店铺评价同步的调度表达式。独立于小时级商品/订单/广告节奏：评价每日至多几十条变更，
     * 日级拉取已足够新鲜，小时级只是无谓的 API 消耗。
     */
    public String resolvedCommentCron() {
        return StringUtils.hasText(commentCron) ? commentCron : "0 30 8 * * *";
    }

    /**
     * 评价同步滚动窗口。默认 31 天，下限 1 天、上限 31 天。
     *
     * <p>窗口需要比订单（3 天）宽：评价可能延迟发布，且买家可事后编辑内容，
     * 宽窗口保证 upsert 能覆盖到变更后的版本。
     */
    public int resolvedCommentWindowDays() {
        if (commentWindowDays <= 0) {
            return 31;
        }
        return Math.max(1, Math.min(commentWindowDays, 31));
    }

    /** 首次回填的默认评价窗口可通过部署配置扩展。 */
    public LocalDate resolvedCommentBackfillStart() {
        return commentBackfillStart == null ? LocalDate.now().minusYears(5) : commentBackfillStart;
    }

    public String resolvedCommentReconcileCron() {
        return StringUtils.hasText(commentReconcileCron) ? commentReconcileCron : "0 10 9 * * SUN";
    }
}
