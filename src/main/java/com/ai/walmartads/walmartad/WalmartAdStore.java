package com.ai.walmartads.walmartad;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import com.ai.walmartads.workspace.WorkspaceContext;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class WalmartAdStore {

    private static final String REPORT_INSERT = """
            insert into wm_ads_lx_ad_report_raw (
                report_date, advertiser_id, ad_type, report_level, entity_key, source, attribution_days,
                campaign_id, campaign_name, campaign_type, campaign_status, targeting_type,
                bidding_strategy, daily_budget, target_roas,
                ad_group_id, ad_group_name, ad_group_status,
                item_id, ad_item_id, sku, parent_sku, ad_name, ad_status, review_status, review_reason,
                keyword_id, keyword_text, keyword_state, keyword_status, match_type, bid, suggested_bid,
                impressions, clicks, ad_spend, attributed_sales, attributed_orders, attributed_units,
                advertised_sku_sales, advertised_sku_units, other_sku_sales, other_sku_units,
                ntb_orders, ntb_revenue, ntb_units, cpc, ctr, cvr, acos, roas, aov, cpa,
                synced_at
            ) values (
                :reportDate, :advertiserId, :adType, :reportLevel, :entityKey, 'LINGXING_MCP', 14,
                :campaignId, :campaignName, :campaignType, :campaignStatus, :targetingType,
                :biddingStrategy, :dailyBudget, :targetRoas,
                :adGroupId, :adGroupName, :adGroupStatus,
                :itemId, :adItemId, :sku, :parentSku, :adName, :adStatus, :reviewStatus, :reviewReason,
                :keywordId, :keywordText, :keywordState, :keywordStatus, :matchType, :bid, :suggestedBid,
                :impressions, :clicks, :adSpend, :attributedSales, :attributedOrders, :attributedUnits,
                :advertisedSkuSales, :advertisedSkuUnits, :otherSkuSales, :otherSkuUnits,
                :ntbOrders, :ntbRevenue, :ntbUnits, :cpc, :ctr, :cvr, :acos, :roas, :aov, :cpa,
                :syncedAt
            )
            """;

    private static final String SLICE_INSERT = """
            insert into wm_ads_lx_ad_slice (
                report_date, advertiser_id, ad_type, slice_type, entity_key, source, attribution_days,
                campaign_id, campaign_name, campaign_type, campaign_status, targeting_type,
                ad_group_id, ad_group_name, dimension_value,
                impressions, clicks, ad_spend, attributed_sales, attributed_orders, attributed_units,
                advertised_sku_sales, advertised_sku_units, other_sku_sales, other_sku_units,
                ntb_orders, ntb_revenue, ntb_units, cpc, ctr, cvr, acos, roas, aov, cpa,
                synced_at
            ) values (
                :reportDate, :advertiserId, :adType, :sliceType, :entityKey, 'LINGXING_MCP', 14,
                :campaignId, :campaignName, :campaignType, :campaignStatus, :targetingType,
                :adGroupId, :adGroupName, :dimensionValue,
                :impressions, :clicks, :adSpend, :attributedSales, :attributedOrders, :attributedUnits,
                :advertisedSkuSales, :advertisedSkuUnits, :otherSkuSales, :otherSkuUnits,
                :ntbOrders, :ntbRevenue, :ntbUnits, :cpc, :ctr, :cvr, :acos, :roas, :aov, :cpa,
                :syncedAt
            )
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactionTemplate;

    public WalmartAdStore(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.transactionTemplate = transactionTemplate;
    }

    public long startLog(String triggeredBy, LocalDate start, LocalDate end) {
        int toolCount = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L) * 18;
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into wm_ads_lx_ad_sync_log (
                        started_at, status, source, triggered_by, window_start, window_end,
                        attribution_days, tool_count, completed_tool_count,
                        report_rows, slice_rows, fetched_count, duration_ms
                    ) values (?, 'RUNNING', 'lingxing_mcp', ?, ?, ?, 14, ?, 0, 0, 0, 0, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, LocalDateTime.now());
            statement.setString(2, triggeredBy);
            statement.setObject(3, start);
            statement.setObject(4, end);
            statement.setInt(5, toolCount);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("Unable to create LingXing ad sync log");
        }
        return key.longValue();
    }

    public void replaceWindow(
            LocalDate start,
            LocalDate end,
            List<Long> advertiserIds,
            List<AdReportRow> reports,
            List<AdSliceRow> slices
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            MapSqlParameterSource delete = new MapSqlParameterSource()
                    .addValue("start", start)
                    .addValue("end", end)
                    .addValue("advertiserIds", advertiserIds);
            namedJdbc.update("""
                    delete from wm_ads_lx_ad_report_raw
                     where report_date between :start and :end
                       and advertiser_id in (:advertiserIds)
                    """, delete);
            namedJdbc.update("""
                    delete from wm_ads_lx_ad_slice
                     where report_date between :start and :end
                       and advertiser_id in (:advertiserIds)
                    """, delete);
            if (!reports.isEmpty()) {
                namedJdbc.batchUpdate(REPORT_INSERT, reports.stream().map(WalmartAdStore::params).toArray(MapSqlParameterSource[]::new));
            }
            if (!slices.isEmpty()) {
                namedJdbc.batchUpdate(SLICE_INSERT, slices.stream().map(WalmartAdStore::params).toArray(MapSqlParameterSource[]::new));
            }
            refreshDashboardSummaries(start, end);
        });
    }

    private void refreshDashboardSummaries(LocalDate start, LocalDate end) {
        long workspaceId = WorkspaceContext.requireId();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("start", start)
                .addValue("end", end);
        for (String table : List.of(
                "wm_ads_dashboard_ad_daily",
                "wm_ads_dashboard_ad_sku_daily",
                "wm_ads_dashboard_ad_campaign_daily")) {
            namedJdbc.update("delete from " + table + " where workspace_id=:workspaceId and report_date between :start and :end",
                    parameters);
        }
        namedJdbc.update("""
                insert into wm_ads_dashboard_ad_daily (
                    workspace_id, report_date, impressions, clicks, ad_spend, attributed_orders, attributed_sales
                )
                select ads.workspace_id, ads.report_date,
                       sum(ads.impressions), sum(ads.clicks), sum(ads.ad_spend),
                       sum(ads.attributed_orders), sum(ads.attributed_sales)
                  from wm_ads_lx_ad_report_raw ads
                  left join (
                      select distinct workspace_id, parent_sku
                        from wm_ads_product_record
                       where workspace_id=:workspaceId and current_version=true
                         and parent_sku is not null and parent_sku <> ''
                  ) product on product.workspace_id=ads.workspace_id and product.parent_sku=ads.parent_sku
                 where ads.workspace_id=:workspaceId and ads.report_level='AD_ITEM'
                   and ads.report_date between :start and :end
                   and (ads.parent_sku is null or ads.parent_sku='' or product.parent_sku is not null)
                 group by ads.workspace_id, ads.report_date
                """, parameters);
        namedJdbc.update("""
                insert into wm_ads_dashboard_ad_sku_daily (
                    workspace_id, report_date, parent_sku, impressions, clicks, ad_spend,
                    attributed_orders, attributed_sales
                )
                select ads.workspace_id, ads.report_date, product.parent_sku,
                       sum(ads.impressions), sum(ads.clicks), sum(ads.ad_spend),
                       sum(ads.attributed_orders), sum(ads.attributed_sales)
                  from wm_ads_lx_ad_report_raw ads
                  join (
                      select distinct workspace_id, parent_sku
                        from wm_ads_product_record
                       where workspace_id=:workspaceId and current_version=true
                         and parent_sku is not null and parent_sku <> ''
                  ) product on product.workspace_id=ads.workspace_id and product.parent_sku=ads.parent_sku
                 where ads.workspace_id=:workspaceId and ads.report_level='AD_ITEM'
                   and ads.report_date between :start and :end
                 group by ads.workspace_id, ads.report_date, product.parent_sku
                """, parameters);
        namedJdbc.update("""
                insert into wm_ads_dashboard_ad_campaign_daily (
                    workspace_id, report_date, campaign_name, campaign_id, ad_type, targeting_type, parent_sku,
                    impressions, clicks, ad_spend, attributed_orders, attributed_sales, average_bid, listed_products
                )
                select ads.workspace_id, ads.report_date, coalesce(ads.campaign_name, '未命名广告'),
                       max(ads.campaign_id), coalesce(ads.ad_type, 'UNKNOWN'), coalesce(ads.targeting_type, ''),
                       coalesce(max(nullif(ads.parent_sku, '')), '未映射SKU'),
                       sum(ads.impressions), sum(ads.clicks), sum(ads.ad_spend),
                       sum(ads.attributed_orders), sum(ads.attributed_sales),
                       case when sum(ads.clicks) > 0 then sum(ads.ad_spend) / sum(ads.clicks)
                            else coalesce(avg(ads.bid), 0) end,
                       count(distinct ads.item_id)
                  from wm_ads_lx_ad_report_raw ads
                  left join (
                      select distinct workspace_id, parent_sku
                        from wm_ads_product_record
                       where workspace_id=:workspaceId and current_version=true
                         and parent_sku is not null and parent_sku <> ''
                  ) product on product.workspace_id=ads.workspace_id and product.parent_sku=ads.parent_sku
                 where ads.workspace_id=:workspaceId and ads.report_level='AD_ITEM'
                   and ads.report_date between :start and :end
                   and (ads.parent_sku is null or ads.parent_sku='' or product.parent_sku is not null)
                 group by ads.workspace_id, ads.report_date, coalesce(ads.campaign_name, '未命名广告'),
                          coalesce(ads.ad_type, 'UNKNOWN'), coalesce(ads.targeting_type, '')
                """, parameters);
    }

    public Map<WalmartAdSyncGuard.CoverageKey, Long> loadCoverage(
            LocalDate start,
            LocalDate end,
            List<Long> advertiserIds
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", end)
                .addValue("advertiserIds", advertiserIds);
        Map<WalmartAdSyncGuard.CoverageKey, Long> coverage = new LinkedHashMap<>();
        namedJdbc.queryForList("""
                select report_date, advertiser_id, ad_type, report_level, count(*) as row_count
                  from wm_ads_lx_ad_report_raw
                 where report_date between :start and :end
                   and advertiser_id in (:advertiserIds)
                 group by report_date, advertiser_id, ad_type, report_level
                """, parameters).forEach(row -> coverage.put(
                new WalmartAdSyncGuard.CoverageKey(
                        toLocalDate(row.get("report_date")),
                        ((Number) row.get("advertiser_id")).longValue(),
                        String.valueOf(row.get("ad_type")),
                        "REPORT:" + row.get("report_level")),
                ((Number) row.get("row_count")).longValue()));
        namedJdbc.queryForList("""
                select report_date, advertiser_id, ad_type, slice_type, count(*) as row_count
                  from wm_ads_lx_ad_slice
                 where report_date between :start and :end
                   and advertiser_id in (:advertiserIds)
                 group by report_date, advertiser_id, ad_type, slice_type
                """, parameters).forEach(row -> coverage.put(
                new WalmartAdSyncGuard.CoverageKey(
                        toLocalDate(row.get("report_date")),
                        ((Number) row.get("advertiser_id")).longValue(),
                        String.valueOf(row.get("ad_type")),
                        "SLICE:" + row.get("slice_type")),
                ((Number) row.get("row_count")).longValue()));
        return coverage;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    public void completeLog(long id, int completedTools, int reportRows, int sliceRows, long durationMs) {
        jdbc.update("""
                update wm_ads_lx_ad_sync_log
                   set finished_at = ?, status = 'SUCCESS', completed_tool_count = ?,
                       report_rows = ?, slice_rows = ?, fetched_count = ?, duration_ms = ?, error_message = null
                 where id = ?
                """, LocalDateTime.now(), completedTools, reportRows, sliceRows,
                reportRows + sliceRows, durationMs, id);
    }

    /**
     * 窗口内全部是「行在、指标全 0」的未结算空壳日：本轮不写库，只把任务收尾成 SKIPPED。
     *
     * <p>以前这种情况会走 {@link #completeLog} 记成 SUCCESS，页面上看起来「同步成功」，
     * 实际一条广告数据都没进来（2026-10-28 06:40 那次就是典型），运营据此判断会误判。
     */
    public void skipLog(long id, int completedTools, String message, long durationMs) {
        jdbc.update("""
                update wm_ads_lx_ad_sync_log
                   set finished_at = ?, status = 'SKIPPED', completed_tool_count = ?,
                       report_rows = 0, slice_rows = 0, fetched_count = 0,
                       error_message = ?, duration_ms = ?
                 where id = ?
                """, LocalDateTime.now(), completedTools, abbreviate(message, 60000), durationMs, id);
    }

    public void failLog(long id, int completedTools, String error, long durationMs) {
        jdbc.update("""
                update wm_ads_lx_ad_sync_log
                   set finished_at = ?, status = 'FAILED', completed_tool_count = ?,
                       error_message = ?, duration_ms = ?
                 where id = ?
                """, LocalDateTime.now(), completedTools, abbreviate(error, 60000), durationMs, id);
    }

    public Map<String, Object> latestLog() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, started_at, finished_at, status, source, triggered_by,
                       window_start, window_end, attribution_days, tool_count,
                       completed_tool_count, report_rows, slice_rows, fetched_count,
                       error_message, duration_ms
                  from wm_ads_lx_ad_sync_log
                 order by started_at desc
                 limit 1
                """);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /**
     * 历史回填的断点：取最后一天「已处理」的日期。
     *
     * <p>{@code SKIPPED} 也算已处理 —— 它表示领星那天确实没有可结算数据，
     * 若不推进断点，回填会永远卡在同一天反复重试，后面的日期永远补不上。
     */
    public LocalDate latestSuccessfulBackfillDate() {
        return jdbc.query("""
                select max(window_end) as latest_date
                  from wm_ads_lx_ad_sync_log
                 where status in ('SUCCESS','SKIPPED') and triggered_by = 'BACKFILL'
                """, resultSet -> resultSet.next() && resultSet.getDate("latest_date") != null
                ? resultSet.getDate("latest_date").toLocalDate()
                : null);
    }

    private static MapSqlParameterSource params(AdReportRow row) {
        return new MapSqlParameterSource()
                .addValue("reportDate", row.reportDate()).addValue("advertiserId", row.advertiserId())
                .addValue("adType", row.adType()).addValue("reportLevel", row.reportLevel())
                .addValue("entityKey", row.entityKey()).addValue("campaignId", row.campaignId())
                .addValue("campaignName", row.campaignName()).addValue("campaignType", row.campaignType())
                .addValue("campaignStatus", row.campaignStatus()).addValue("targetingType", row.targetingType())
                .addValue("biddingStrategy", row.biddingStrategy()).addValue("dailyBudget", row.dailyBudget())
                .addValue("targetRoas", row.targetRoas()).addValue("adGroupId", row.adGroupId())
                .addValue("adGroupName", row.adGroupName()).addValue("adGroupStatus", row.adGroupStatus())
                .addValue("itemId", row.itemId()).addValue("adItemId", row.adItemId())
                .addValue("sku", row.sku()).addValue("parentSku", row.parentSku())
                .addValue("adName", row.adName()).addValue("adStatus", row.adStatus())
                .addValue("reviewStatus", row.reviewStatus()).addValue("reviewReason", row.reviewReason())
                .addValue("keywordId", row.keywordId()).addValue("keywordText", row.keywordText())
                .addValue("keywordState", row.keywordState()).addValue("keywordStatus", row.keywordStatus())
                .addValue("matchType", row.matchType()).addValue("bid", row.bid())
                .addValue("suggestedBid", row.suggestedBid()).addValue("impressions", row.impressions())
                .addValue("clicks", row.clicks()).addValue("adSpend", row.adSpend())
                .addValue("attributedSales", row.attributedSales()).addValue("attributedOrders", row.attributedOrders())
                .addValue("attributedUnits", row.attributedUnits()).addValue("advertisedSkuSales", row.advertisedSkuSales())
                .addValue("advertisedSkuUnits", row.advertisedSkuUnits()).addValue("otherSkuSales", row.otherSkuSales())
                .addValue("otherSkuUnits", row.otherSkuUnits()).addValue("ntbOrders", row.ntbOrders())
                .addValue("ntbRevenue", row.ntbRevenue()).addValue("ntbUnits", row.ntbUnits())
                .addValue("cpc", row.cpc()).addValue("ctr", row.ctr()).addValue("cvr", row.cvr())
                .addValue("acos", row.acos()).addValue("roas", row.roas()).addValue("aov", row.aov())
                .addValue("cpa", row.cpa())
                .addValue("syncedAt", row.syncedAt());
    }

    private static MapSqlParameterSource params(AdSliceRow row) {
        return new MapSqlParameterSource()
                .addValue("reportDate", row.reportDate()).addValue("advertiserId", row.advertiserId())
                .addValue("adType", row.adType()).addValue("sliceType", row.sliceType())
                .addValue("entityKey", row.entityKey()).addValue("campaignId", row.campaignId())
                .addValue("campaignName", row.campaignName()).addValue("campaignType", row.campaignType())
                .addValue("campaignStatus", row.campaignStatus()).addValue("targetingType", row.targetingType())
                .addValue("adGroupId", row.adGroupId()).addValue("adGroupName", row.adGroupName())
                .addValue("dimensionValue", row.dimensionValue()).addValue("impressions", row.impressions())
                .addValue("clicks", row.clicks()).addValue("adSpend", row.adSpend())
                .addValue("attributedSales", row.attributedSales()).addValue("attributedOrders", row.attributedOrders())
                .addValue("attributedUnits", row.attributedUnits()).addValue("advertisedSkuSales", row.advertisedSkuSales())
                .addValue("advertisedSkuUnits", row.advertisedSkuUnits()).addValue("otherSkuSales", row.otherSkuSales())
                .addValue("otherSkuUnits", row.otherSkuUnits()).addValue("ntbOrders", row.ntbOrders())
                .addValue("ntbRevenue", row.ntbRevenue()).addValue("ntbUnits", row.ntbUnits())
                .addValue("cpc", row.cpc()).addValue("ctr", row.ctr()).addValue("cvr", row.cvr())
                .addValue("acos", row.acos()).addValue("roas", row.roas()).addValue("aov", row.aov())
                .addValue("cpa", row.cpa())
                .addValue("syncedAt", row.syncedAt());
    }

    private static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
