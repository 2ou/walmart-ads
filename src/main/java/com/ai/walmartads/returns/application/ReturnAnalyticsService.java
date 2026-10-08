package com.ai.walmartads.returns.application;

import com.ai.walmartads.common.WalmartImageUrl;
import com.ai.walmartads.product.application.ProfitAssumptionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReturnAnalyticsService {

    private static final String RETURN_CTE = ReturnQuerySupport.ACTIVE_RETURN_CTE + """
            , current_mapping as (
                select mapping.item_id,
                       max(mapping.sku) as sku,
                       max(mapping.parent_sku) as parent_sku
                  from wm_ads_sku_mapping mapping
                  join wm_ads_import_file file
                    on file.id = mapping.import_file_id
                   and file.active = true
                 where mapping.current_version = true
                 group by mapping.item_id
            ),
            return_rows as (
                select returns.*,
                       coalesce(returns.sku, mapping.sku) as resolved_sku,
                       coalesce(returns.parent_sku, mapping.parent_sku) as resolved_parent_sku
                  from active_return_rows returns
                  left join current_mapping mapping on mapping.item_id = returns.item_id
            ),
            current_products as (
                select product.sku,
                       max(product.parent_sku) as parent_sku,
                       max(product.product_name) as product_name,
                       max(product.main_image_url) as image_url,
                       max(product.color) as color,
                       max(product.size) as size
                  from wm_ads_product_record product
                  join wm_ads_import_file file
                    on file.id = product.import_file_id
                   and file.active = true
                 where product.current_version = true
                 group by product.sku
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProfitAssumptionRepository profitAssumptionRepository;

    public ReturnAnalyticsService(
            NamedParameterJdbcTemplate jdbc,
            ProfitAssumptionRepository profitAssumptionRepository
    ) {
        this.jdbc = jdbc;
        this.profitAssumptionRepository = profitAssumptionRepository;
    }

    public ReturnPayload load(String requestedMonth) {
        Bounds bounds = loadBounds();
        if (bounds == null) {
            return emptyPayload();
        }
        YearMonth selected = parseMonth(requestedMonth, bounds.latestReportMonth());
        LocalDate start = selected.atDay(1);
        LocalDate end = selected.atEndOfMonth();
        MapSqlParameterSource window = new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", end);

        Summary summary = loadSummary(window);
        List<ParentMonthlyRow> parentTrend = loadParentTrend(bounds);
        Set<String> catalogParents = new LinkedHashSet<>();
        parentTrend.forEach(row -> catalogParents.add(row.parentSku()));
        Map<String, SkuTotal> skuTotals = loadSkuTotals(bounds);
        List<SkuRow> skuRows = withSkuTotals(loadSkuRows(window), skuTotals).stream()
                .filter(row -> catalogParents.contains(row.parentSku()))
                .toList();
        Map<String, ParentTotal> parentTotals = loadParentTotals(bounds);
        List<ParentRow> parentRows = parentRows(skuRows, parentTotals);
        List<ParentBreakdown> parentBreakdowns = parentBreakdowns(skuRows);
        List<DetailRow> details = loadDetails(window).stream()
                .filter(row -> catalogParents.contains(row.parentSku()))
                .toList();
        List<BreakdownRow> reasons = breakdown(details, DetailRow::returnReason);
        List<BreakdownRow> statuses = breakdown(details, DetailRow::currentStatus);
        List<BreakdownRow> channels = breakdown(details, DetailRow::channel);
        List<BreakdownRow> refundModes = breakdown(details, DetailRow::refundMode);
        List<BreakdownRow> coveredBy = breakdown(details, DetailRow::refundCoveredBy);
        List<MonthlyRow> trend = loadTrend(bounds);
        AccountSummary accountSummary = loadAccountSummary(bounds);
        Map<String, BigDecimal> manualRefundRates = manualRefundRates();

        return new ReturnPayload(
                new Meta(
                        bounds.firstMonth().toString(),
                        bounds.latestMonth().toString(),
                        bounds.latestReportMonth().toString(),
                        selected.toString(),
                        start,
                        end,
                        bounds.reportMonths(),
                        "USD",
                        "退款件数 ÷ 退款报表导入时间范围内的订单销量（" + bounds.reportStartMonth()
                                + " 至 " + bounds.reportEndMonth() + "，按款号汇总）",
                        "Return Invoice Status = RETURN_INVOICED"
                ),
                summary,
                accountSummary,
                trend,
                parentTrend,
                parentRows,
                skuRows,
                parentBreakdowns,
                reasons,
                statuses,
                channels,
                refundModes,
                coveredBy,
                details,
                manualRefundRates
        );
    }

    /**
     * 产品库手填的「手工退款率」，按 parentSku 维度（wm_ads_profit_assumption.refund_rate）。
     * 仅取非空值，供退款率下钻的「预估退款率」列展示。
     */
    private Map<String, BigDecimal> manualRefundRates() {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (var entity : profitAssumptionRepository.findAll()) {
            if (entity.getRefundRate() != null) {
                result.put(entity.getParentSku(), entity.getRefundRate());
            }
        }
        return result;
    }

    private AccountSummary loadAccountSummary(Bounds bounds) {
        MapSqlParameterSource period = new MapSqlParameterSource()
                .addValue("reportStart", bounds.reportStartMonth().atDay(1))
                .addValue("reportEnd", bounds.reportEndMonth().atEndOfMonth());
        Map<String, Object> returns = jdbc.queryForMap(
                RETURN_CTE + """
                        select coalesce(sum(quantity), 0) as requested_units,
                               coalesce(sum(case when current_status = 'RETURN_INVOICED'
                                                 then quantity else 0 end), 0) as completed_units,
                               coalesce(sum(case when current_status = 'RETURN_INVOICED'
                                                 then total_refund_amount else 0 end), 0) as refund_amount
                          from return_rows
                         where report_month between :reportStart and :reportEnd
                        """,
                period
        );
        Map<String, Object> sales = jdbc.queryForMap("""
                select coalesce(sum(orders.quantity), 0) as sold_units,
                       coalesce(sum(orders.quantity * orders.item_cost), 0) as sales_revenue
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file
                    on file.id = orders.import_file_id
                   and file.active = true
                 where orders.order_date between :reportStart and :reportEnd
                """, period);
        return accountSummary(
                bounds.reportMonths(),
                number(returns, "requested_units"),
                number(returns, "completed_units"),
                number(sales, "sold_units"),
                number(returns, "refund_amount"),
                number(sales, "sales_revenue")
        );
    }

    static AccountSummary accountSummary(
            List<String> reportMonths,
            double requested,
            double completed,
            double sold,
            double refundAmount,
            double salesRevenue
    ) {
        List<String> includedMonths = reportMonths.stream()
                .distinct()
                .sorted()
                .toList();
        return new AccountSummary(
                includedMonths.isEmpty() ? null : includedMonths.getFirst(),
                includedMonths.isEmpty() ? null : includedMonths.getLast(),
                includedMonths.size(),
                round(requested),
                round(completed),
                round(sold),
                ratio(requested, sold),
                ratio(completed, sold),
                round(refundAmount),
                round(salesRevenue),
                ratio(refundAmount, salesRevenue)
        );
    }

    private Bounds loadBounds() {
        Map<String, Object> row = jdbc.queryForMap(
                RETURN_CTE + """
                        select min(request_date) as first_date,
                               max(request_date) as latest_date,
                               max(report_month) as latest_report_month,
                               min(report_month) as report_start_month,
                               max(report_month) as report_end_month,
                               group_concat(distinct date_format(report_month, '%Y-%m')
                                            order by report_month separator ',') as report_months
                          from return_rows
                        """,
                new MapSqlParameterSource()
        );
        LocalDate first = localDate(row.get("first_date"));
        LocalDate latest = localDate(row.get("latest_date"));
        LocalDate latestReport = localDate(row.get("latest_report_month"));
        LocalDate reportStart = localDate(row.get("report_start_month"));
        LocalDate reportEnd = localDate(row.get("report_end_month"));
        if (first == null || latest == null || latestReport == null || reportStart == null || reportEnd == null) {
            return null;
        }
        List<String> reportMonths = text(row, "report_months") == null
                ? List.of()
                : List.of(text(row, "report_months").split(","));
        return new Bounds(
                YearMonth.from(first),
                YearMonth.from(latest),
                YearMonth.from(latestReport),
                reportMonths,
                YearMonth.from(reportStart),
                YearMonth.from(reportEnd)
        );
    }

    /**
     * 款号累计总退款率始终以退款导入文件的 report_month 首末月份为统计区间。
     * 退款分子只计算该区间内实际已退款件数，销量分母只计算同一区间内的订单。
     */
    private Map<String, ParentTotal> loadParentTotals(Bounds bounds) {
        MapSqlParameterSource period = new MapSqlParameterSource()
                .addValue("reportStart", bounds.reportStartMonth().atDay(1))
                .addValue("reportEnd", bounds.reportEndMonth().atEndOfMonth());
        Map<String, ParentTotal> result = new LinkedHashMap<>();
        jdbc.queryForList(
                RETURN_CTE + """
                        , return_by_parent as (
                            select coalesce(returns.resolved_parent_sku, product.parent_sku) as parent_sku,
                                   sum(case when returns.current_status = 'RETURN_INVOICED'
                                            then returns.quantity else 0 end) as completed_units
                              from return_rows returns
                              left join current_products product on product.sku = returns.resolved_sku
                             where returns.report_month between :reportStart and :reportEnd
                             group by coalesce(returns.resolved_parent_sku, product.parent_sku)
                        ),
                        sales_by_parent as (
                            select product.parent_sku,
                                   sum(orders.quantity) as sold_units
                              from wm_ads_order_record_valid orders
                              join wm_ads_import_file file
                                on file.id = orders.import_file_id
                               and file.active = true
                              join current_products product on product.sku = orders.sku
                             where orders.order_date between :reportStart and :reportEnd
                               and nullif(trim(product.parent_sku), '') is not null
                             group by product.parent_sku
                        )
                        select returned.parent_sku,
                               coalesce(returned.completed_units, 0) as completed_units,
                               coalesce(sales.sold_units, 0) as sold_units
                          from return_by_parent returned
                          left join sales_by_parent sales on sales.parent_sku = returned.parent_sku
                         where nullif(trim(returned.parent_sku), '') is not null
                        """,
                period
        ).forEach(row -> {
            String parentSku = text(row, "parent_sku");
            double completed = number(row, "completed_units");
            double sold = number(row, "sold_units");
            result.put(parentSku, new ParentTotal(
                    parentSku,
                    round(completed),
                    round(sold),
                    ratio(completed, sold)
            ));
        });
        return result;
    }

    /**
     * SKU 累计退款口径与款号累计口径一致：退款文件 report_month 首末月份内的已退款件数，
     * 除以该区间内同一 SKU 的订单销量。groupKey 同时兼容未映射 SKU 的 ITEM:xxx 记录。
     */
    private Map<String, SkuTotal> loadSkuTotals(Bounds bounds) {
        MapSqlParameterSource period = new MapSqlParameterSource()
                .addValue("reportStart", bounds.reportStartMonth().atDay(1))
                .addValue("reportEnd", bounds.reportEndMonth().atEndOfMonth());
        Map<String, SkuTotal> result = new LinkedHashMap<>();
        jdbc.queryForList(
                RETURN_CTE + """
                        , return_by_sku as (
                            select coalesce(returns.resolved_sku, concat('ITEM:', returns.item_id)) as group_key,
                                   sum(case when returns.current_status = 'RETURN_INVOICED'
                                            then returns.quantity else 0 end) as completed_units
                              from return_rows returns
                             where returns.report_month between :reportStart and :reportEnd
                             group by coalesce(returns.resolved_sku, concat('ITEM:', returns.item_id))
                        ),
                        sales_by_sku as (
                            select orders.sku as group_key,
                                   sum(orders.quantity) as sold_units
                              from wm_ads_order_record_valid orders
                              join wm_ads_import_file file
                                on file.id = orders.import_file_id
                               and file.active = true
                             where orders.order_date between :reportStart and :reportEnd
                             group by orders.sku
                        ),
                        sku_universe as (
                            select group_key from return_by_sku
                            union
                            select group_key from sales_by_sku
                        )
                        select universe.group_key,
                               coalesce(returned.completed_units, 0) as completed_units,
                               coalesce(sales.sold_units, 0) as sold_units
                          from sku_universe universe
                          left join return_by_sku returned on returned.group_key = universe.group_key
                          left join sales_by_sku sales on sales.group_key = universe.group_key
                        """,
                period
        ).forEach(row -> {
            String groupKey = text(row, "group_key");
            double completed = number(row, "completed_units");
            double sold = number(row, "sold_units");
            result.put(groupKey, new SkuTotal(
                    groupKey,
                    round(completed),
                    round(sold),
                    ratio(completed, sold),
                    refundRisk(completed, sold)
            ));
        });
        return result;
    }

    private static List<SkuRow> withSkuTotals(List<SkuRow> rows, Map<String, SkuTotal> totals) {
        return rows.stream().map(row -> {
            SkuTotal total = totals.get(row.groupKey());
            return new SkuRow(
                    row.groupKey(),
                    row.sku(),
                    row.parentSku(),
                    row.itemId(),
                    row.itemName(),
                    row.imageUrl(),
                    row.color(),
                    row.size(),
                    row.requestedUnits(),
                    row.completedUnits(),
                    row.cancelledUnits(),
                    row.pendingUnits(),
                    row.soldUnits(),
                    row.requestRate(),
                    row.completedRate(),
                    row.refundAmount(),
                    row.salesRevenue(),
                    row.refundAmountRate(),
                    row.reasonSummary(),
                    row.risk(),
                    total == null ? 0 : total.completedUnits(),
                    total == null ? 0 : total.soldUnits(),
                    total == null ? 0 : total.completedRate(),
                    total == null ? "NO_SALES_DENOMINATOR" : total.risk()
            );
        }).toList();
    }

    /**
     * 累计款号退款率（退款率分析口径）：以退款导入文件 report_month 首末月份为统计区间，
     * 分子=区间内 RETURN_INVOICED 件数，分母=同区间内订单销量，按 parentSku 返回。
     * 无退款记录的款号不出现在结果中（调用方按 0.0 兜底）；
     * 系统完全没有退款数据时返回 null（用于前端区分「无数据」与「0%」）。
     */
    public Map<String, Double> cumulativeRefundRateByParent() {
        Bounds bounds = loadBounds();
        if (bounds == null) {
            return null;
        }
        Map<String, Double> result = new LinkedHashMap<>();
        loadParentTotals(bounds).forEach((parentSku, total) ->
                result.put(parentSku, total.completedRate()));
        return result;
    }

    private Summary loadSummary(MapSqlParameterSource window) {
        Map<String, Object> returns = jdbc.queryForMap(
                RETURN_CTE + """
                        select coalesce(sum(quantity), 0) as requested_units,
                               coalesce(sum(case when current_status = 'RETURN_INVOICED'
                                                 then quantity else 0 end), 0) as completed_units,
                               coalesce(sum(case when current_status = 'CANCELLED'
                                                 then quantity else 0 end), 0) as cancelled_units,
                               coalesce(sum(case when current_status <> 'RETURN_INVOICED'
                                                  and current_status <> 'CANCELLED'
                                                 then quantity else 0 end), 0) as pending_units,
                               coalesce(sum(case when current_status = 'RETURN_INVOICED'
                                                 then total_refund_amount else 0 end), 0) as refund_amount,
                               coalesce(sum(case when current_status = 'RETURN_INVOICED'
                                                  and refund_covered_by like 'Seller%'
                                                 then total_refund_amount else 0 end), 0) as seller_amount,
                               coalesce(sum(case when current_status = 'RETURN_INVOICED'
                                                  and refund_covered_by like 'Walmart%'
                                                 then total_refund_amount else 0 end), 0) as walmart_amount,
                               coalesce(sum(case when keep_it then quantity else 0 end), 0) as keep_it_units,
                               coalesce(sum(case when resolved_sku is not null then quantity else 0 end), 0) as mapped_units,
                               count(distinct business_key) as return_lines,
                               count(distinct resolved_sku) as affected_skus,
                               count(distinct resolved_parent_sku) as affected_parents
                          from return_rows
                         where request_date between :start and :end
                        """,
                window
        );
        Map<String, Object> sales = jdbc.queryForMap("""
                select coalesce(sum(orders.quantity), 0) as sold_units,
                       coalesce(sum(orders.quantity * orders.item_cost), 0) as sales_revenue
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file
                    on file.id = orders.import_file_id
                   and file.active = true
                 where orders.order_date between :start and :end
                """, window);

        double requested = number(returns, "requested_units");
        double completed = number(returns, "completed_units");
        double sold = number(sales, "sold_units");
        double refundAmount = number(returns, "refund_amount");
        double salesRevenue = number(sales, "sales_revenue");
        return new Summary(
                round(requested),
                round(completed),
                round(number(returns, "cancelled_units")),
                round(number(returns, "pending_units")),
                round(sold),
                ratio(requested, sold),
                ratio(completed, sold),
                round(refundAmount),
                round(salesRevenue),
                ratio(refundAmount, salesRevenue),
                round(number(returns, "seller_amount")),
                round(number(returns, "walmart_amount")),
                round(number(returns, "keep_it_units")),
                longNumber(returns, "return_lines"),
                longNumber(returns, "affected_skus"),
                longNumber(returns, "affected_parents"),
                ratio(number(returns, "mapped_units"), requested)
        );
    }

    private List<MonthlyRow> loadTrend(Bounds bounds) {
        Map<String, MonthlyAccumulator> months = new LinkedHashMap<>();
        jdbc.queryForList(
                RETURN_CTE + """
                        select date_format(request_date, '%Y-%m') as month,
                               sum(quantity) as requested_units,
                               sum(case when current_status = 'RETURN_INVOICED'
                                        then quantity else 0 end) as completed_units,
                               sum(case when current_status = 'CANCELLED'
                                        then quantity else 0 end) as cancelled_units,
                               sum(case when current_status <> 'RETURN_INVOICED'
                                         and current_status <> 'CANCELLED'
                                        then quantity else 0 end) as pending_units,
                               sum(case when current_status = 'RETURN_INVOICED'
                                        then total_refund_amount else 0 end) as refund_amount
                          from return_rows
                         group by date_format(request_date, '%Y-%m')
                         order by month
                        """,
                new MapSqlParameterSource()
        ).forEach(row -> {
            String month = text(row, "month");
            months.put(month, new MonthlyAccumulator(
                    month,
                    number(row, "requested_units"),
                    number(row, "completed_units"),
                    number(row, "cancelled_units"),
                    number(row, "pending_units"),
                    number(row, "refund_amount")
            ));
        });
        MapSqlParameterSource range = new MapSqlParameterSource()
                .addValue("trendStart", bounds.firstMonth().atDay(1))
                .addValue("trendEnd", bounds.latestMonth().atEndOfMonth());
        jdbc.queryForList("""
                select date_format(orders.order_date, '%Y-%m') as month,
                       sum(orders.quantity) as sold_units,
                       sum(orders.quantity * orders.item_cost) as sales_revenue
                  from wm_ads_order_record_valid orders
                 join wm_ads_import_file file
                    on file.id = orders.import_file_id
                   and file.active = true
                 where orders.order_date between :trendStart and :trendEnd
                 group by date_format(orders.order_date, '%Y-%m')
                 order by month
                """, range).forEach(row -> {
            String month = text(row, "month");
            months.computeIfAbsent(month, MonthlyAccumulator::new)
                    .sales(number(row, "sold_units"), number(row, "sales_revenue"));
        });
        return months.values().stream()
                .sorted(Comparator.comparing(MonthlyAccumulator::month))
                .map(MonthlyAccumulator::row)
                .toList();
    }

    private List<ParentMonthlyRow> loadParentTrend(Bounds bounds) {
        MapSqlParameterSource range = new MapSqlParameterSource()
                .addValue("trendStart", bounds.firstMonth().atDay(1))
                .addValue("trendEnd", bounds.latestMonth().atEndOfMonth());
        List<Map<String, Object>> raw = jdbc.queryForList(
                RETURN_CTE + """
                        , catalog_parents as (
                            select product.parent_sku,
                                   max(product.product_name) as item_name
                              from current_products product
                             where nullif(trim(product.parent_sku), '') is not null
                             group by product.parent_sku
                        ),
                        return_by_parent_month as (
                            select date_format(returns.request_date, '%Y-%m') as month,
                                   catalog.parent_sku,
                                   max(catalog.item_name) as item_name,
                                   sum(returns.quantity) as requested_units,
                                   sum(case when returns.current_status = 'RETURN_INVOICED'
                                            then returns.quantity else 0 end) as completed_units,
                                   sum(case when returns.current_status = 'CANCELLED'
                                            then returns.quantity else 0 end) as cancelled_units,
                                   sum(case when returns.current_status <> 'RETURN_INVOICED'
                                             and returns.current_status <> 'CANCELLED'
                                            then returns.quantity else 0 end) as pending_units,
                                   sum(case when returns.current_status = 'RETURN_INVOICED'
                                            then returns.total_refund_amount else 0 end) as refund_amount
                              from return_rows returns
                              join current_products product on product.sku = returns.resolved_sku
                              join catalog_parents catalog on catalog.parent_sku = product.parent_sku
                             where returns.request_date between :trendStart and :trendEnd
                             group by date_format(returns.request_date, '%Y-%m'),
                                      catalog.parent_sku
                        ),
                        sales_by_parent_month as (
                            select date_format(orders.order_date, '%Y-%m') as month,
                                   product.parent_sku,
                                   sum(orders.quantity) as sold_units,
                                   sum(orders.quantity * orders.item_cost) as sales_revenue
                              from wm_ads_order_record_valid orders
                              join wm_ads_import_file file
                                on file.id = orders.import_file_id
                               and file.active = true
                              join current_products product on product.sku = orders.sku
                             where orders.order_date between :trendStart and :trendEnd
                               and nullif(trim(product.parent_sku), '') is not null
                             group by date_format(orders.order_date, '%Y-%m'), product.parent_sku
                        ),
                        data_months as (
                            select distinct date_format(returns.request_date, '%Y-%m') as month
                              from return_rows returns
                             where returns.request_date between :trendStart and :trendEnd
                            union
                            select distinct date_format(orders.order_date, '%Y-%m') as month
                              from wm_ads_order_record_valid orders
                              join wm_ads_import_file file
                                on file.id = orders.import_file_id
                               and file.active = true
                             where orders.order_date between :trendStart and :trendEnd
                        ),
                        parent_months as (
                            select catalog.parent_sku,
                                   catalog.item_name,
                                   months.month
                              from catalog_parents catalog
                              cross join data_months months
                        )
                        select series.parent_sku,
                               coalesce(series.item_name, series.parent_sku) as item_name,
                               series.month,
                               coalesce(max(returned.requested_units), 0) as requested_units,
                               coalesce(max(returned.completed_units), 0) as completed_units,
                               coalesce(max(returned.cancelled_units), 0) as cancelled_units,
                               coalesce(max(returned.pending_units), 0) as pending_units,
                               coalesce(max(sales.sold_units), 0) as sold_units,
                               coalesce(max(returned.refund_amount), 0) as refund_amount,
                               coalesce(max(sales.sales_revenue), 0) as sales_revenue
                          from parent_months series
                          left join return_by_parent_month returned
                            on returned.parent_sku = series.parent_sku
                           and returned.month = series.month
                          left join sales_by_parent_month sales
                            on sales.parent_sku = series.parent_sku
                           and sales.month = series.month
                         group by series.parent_sku, series.item_name, series.month
                         order by series.parent_sku, series.month
                        """,
                range
        ).stream().toList();
        return raw.stream().map(row -> {
            double requested = number(row, "requested_units");
            double completed = number(row, "completed_units");
            double sold = number(row, "sold_units");
            double refundAmount = number(row, "refund_amount");
            double salesRevenue = number(row, "sales_revenue");
            return new ParentMonthlyRow(
                    text(row, "parent_sku"),
                    text(row, "item_name"),
                    text(row, "month"),
                    round(requested),
                    round(completed),
                    round(number(row, "cancelled_units")),
                    round(number(row, "pending_units")),
                    round(sold),
                    ratio(requested, sold),
                    ratio(completed, sold),
                    round(refundAmount),
                    round(salesRevenue),
                    ratio(refundAmount, salesRevenue)
            );
        }).toList();
    }

    private List<SkuRow> loadSkuRows(MapSqlParameterSource window) {
        List<SkuRow> result = new ArrayList<>();
        jdbc.queryForList(
                RETURN_CTE + """
                        , return_by_sku as (
                            select coalesce(returns.resolved_sku, concat('ITEM:', returns.item_id)) as group_key,
                                   max(returns.resolved_sku) as sku,
                                   max(returns.item_id) as item_id,
                                   max(returns.resolved_parent_sku) as resolved_parent_sku,
                                   max(returns.item_name) as return_item_name,
                                   sum(returns.quantity) as requested_units,
                                   sum(case when returns.current_status = 'RETURN_INVOICED'
                                            then returns.quantity else 0 end) as completed_units,
                                   sum(case when returns.current_status = 'CANCELLED'
                                            then returns.quantity else 0 end) as cancelled_units,
                                   sum(case when returns.current_status <> 'RETURN_INVOICED'
                                             and returns.current_status <> 'CANCELLED'
                                            then returns.quantity else 0 end) as pending_units,
                                   sum(case when returns.current_status = 'RETURN_INVOICED'
                                            then returns.total_refund_amount else 0 end) as refund_amount,
                                   group_concat(distinct returns.return_reason
                                                order by returns.return_reason separator ' · ') as reason_summary
                              from return_rows returns
                             where returns.request_date between :start and :end
                             group by coalesce(returns.resolved_sku, concat('ITEM:', returns.item_id))
                        ),
                        monthly_sales as (
                            select orders.sku,
                                   sum(orders.quantity) as sold_units,
                                   sum(orders.quantity * orders.item_cost) as sales_revenue
                              from wm_ads_order_record_valid orders
                              join wm_ads_import_file file
                                on file.id = orders.import_file_id
                               and file.active = true
                             where orders.order_date between :start and :end
                              group by orders.sku
                        ),
                        affected_parents as (
                            select distinct coalesce(returned.resolved_parent_sku, product.parent_sku) as parent_sku
                              from return_by_sku returned
                              left join current_products product on product.sku = returned.sku
                             where coalesce(returned.resolved_parent_sku, product.parent_sku) is not null
                        ),
                        sku_universe as (
                            select returned.group_key,
                                   returned.sku
                              from return_by_sku returned
                            union
                            select sales.sku as group_key,
                                   sales.sku
                              from monthly_sales sales
                              join current_products product on product.sku = sales.sku
                              join affected_parents affected on affected.parent_sku = product.parent_sku
                        )
                        select universe.group_key,
                               coalesce(returned.sku, universe.sku) as sku,
                               returned.item_id,
                               coalesce(returned.resolved_parent_sku, product.parent_sku) as parent_sku,
                               coalesce(product.product_name, returned.return_item_name) as item_name,
                               product.image_url,
                               product.color,
                               product.size,
                               coalesce(returned.requested_units, 0) as requested_units,
                               coalesce(returned.completed_units, 0) as completed_units,
                               coalesce(returned.cancelled_units, 0) as cancelled_units,
                               coalesce(returned.pending_units, 0) as pending_units,
                               coalesce(returned.refund_amount, 0) as refund_amount,
                               coalesce(sales.sold_units, 0) as sold_units,
                               coalesce(sales.sales_revenue, 0) as sales_revenue,
                               returned.reason_summary
                          from sku_universe universe
                          left join return_by_sku returned on returned.group_key = universe.group_key
                          left join current_products product on product.sku = universe.sku
                          left join monthly_sales sales on sales.sku = universe.sku
                         order by requested_units desc, refund_amount desc, sold_units desc, group_key
                        """,
                window
        ).forEach(row -> {
            double requested = number(row, "requested_units");
            double completed = number(row, "completed_units");
            double sold = number(row, "sold_units");
            double refundAmount = number(row, "refund_amount");
            double revenue = number(row, "sales_revenue");
            result.add(new SkuRow(
                    text(row, "group_key"),
                    text(row, "sku"),
                    text(row, "parent_sku"),
                    text(row, "item_id"),
                    text(row, "item_name"),
                    WalmartImageUrl.thumb(text(row, "image_url")),
                    text(row, "color"),
                    text(row, "size"),
                    round(requested),
                    round(completed),
                    round(number(row, "cancelled_units")),
                    round(number(row, "pending_units")),
                    round(sold),
                    ratio(requested, sold),
                    ratio(completed, sold),
                    round(refundAmount),
                    round(revenue),
                    ratio(refundAmount, revenue),
                    text(row, "reason_summary"),
                    refundRisk(completed, sold),
                    0,
                    0,
                    0,
                    "NO_SALES_DENOMINATOR"
            ));
        });
        return result;
    }

    private List<DetailRow> loadDetails(MapSqlParameterSource window) {
        List<DetailRow> result = new ArrayList<>();
        jdbc.queryForList(
                RETURN_CTE + """
                        select returns.request_at,
                               returns.report_month,
                               returns.customer_order_no,
                               returns.po_no,
                               returns.po_line_no,
                               returns.rma_no,
                               returns.return_order_line_no,
                               returns.quantity,
                               returns.return_reason,
                               returns.item_id,
                               returns.resolved_sku as sku,
                               coalesce(returns.resolved_parent_sku, product.parent_sku) as parent_sku,
                               coalesce(product.product_name, returns.item_name) as item_name,
                               product.image_url,
                               product.color,
                               product.size,
                               returns.condition_name,
                               returns.current_status,
                               returns.current_status_time,
                               returns.keep_it,
                               returns.carrier,
                               returns.tracking_number,
                               returns.channel,
                               returns.customer_comments,
                               returns.order_amount,
                               returns.return_shipping_fee,
                               returns.restocking_fee,
                               returns.total_refund_amount,
                               returns.seller_order_no,
                               returns.refund_mode,
                               returns.refund_covered_by,
                               returns.tracking_status,
                               returns.refund_status,
                               returns.replacement,
                               returns.platform_type,
                               returns.return_ship_from,
                               returns.return_ship_to
                          from return_rows returns
                          left join current_products product on product.sku = returns.resolved_sku
                         where returns.request_date between :start and :end
                         order by returns.request_at desc, returns.id desc
                        """,
                window
        ).forEach(row -> result.add(new DetailRow(
                localDateTime(row.get("request_at")),
                localDate(row.get("report_month")),
                text(row, "customer_order_no"),
                text(row, "po_no"),
                text(row, "po_line_no"),
                text(row, "rma_no"),
                text(row, "return_order_line_no"),
                round(number(row, "quantity")),
                text(row, "return_reason"),
                text(row, "item_id"),
                text(row, "sku"),
                text(row, "parent_sku"),
                text(row, "item_name"),
                WalmartImageUrl.thumb(text(row, "image_url")),
                text(row, "color"),
                text(row, "size"),
                text(row, "condition_name"),
                text(row, "current_status"),
                localDateTime(row.get("current_status_time")),
                bool(row.get("keep_it")),
                text(row, "carrier"),
                text(row, "tracking_number"),
                text(row, "channel"),
                text(row, "customer_comments"),
                round(number(row, "order_amount")),
                round(number(row, "return_shipping_fee")),
                round(number(row, "restocking_fee")),
                round(number(row, "total_refund_amount")),
                text(row, "seller_order_no"),
                text(row, "refund_mode"),
                text(row, "refund_covered_by"),
                text(row, "tracking_status"),
                text(row, "refund_status"),
                bool(row.get("replacement")),
                text(row, "platform_type"),
                text(row, "return_ship_from"),
                text(row, "return_ship_to")
        )));
        return result;
    }

    private static List<ParentRow> parentRows(
            List<SkuRow> rows,
            Map<String, ParentTotal> totals
    ) {
        Map<String, ParentAccumulator> groups = new LinkedHashMap<>();
        rows.forEach(row -> {
            String key = firstText(row.parentSku(), row.sku(), row.itemId(), "未映射");
            groups.computeIfAbsent(key, ParentAccumulator::new).add(row);
        });
        return groups.values().stream()
                .map(item -> item.row(totals.get(item.parentSku)))
                .sorted(Comparator.comparingDouble(ParentRow::requestedUnits).reversed()
                        .thenComparing(ParentRow::parentSku))
                .toList();
    }

    private static List<DimensionRow> dimensionRows(List<SkuRow> rows, boolean color) {
        double parentCompleted = rows.stream().mapToDouble(SkuRow::completedUnits).sum();
        Map<String, DimensionAccumulator> groups = new LinkedHashMap<>();
        rows.forEach(row -> {
            String label = color ? row.color() : row.size();
            label = label == null || label.isBlank() ? "未识别" : label.trim();
            groups.computeIfAbsent(label, DimensionAccumulator::new).add(row);
        });
        return groups.values().stream()
                .map(item -> item.row(parentCompleted))
                .sorted(Comparator.comparingInt((DimensionRow row) -> refundRiskPriority(row.totalRisk())).reversed()
                        .thenComparing(Comparator.comparingDouble(DimensionRow::totalCompletedUnits).reversed())
                        .thenComparing(Comparator.comparingDouble(DimensionRow::totalCompletedRate).reversed())
                        .thenComparing(DimensionRow::label))
                .toList();
    }

    private static List<ParentBreakdown> parentBreakdowns(List<SkuRow> rows) {
        Map<String, List<SkuRow>> groups = new LinkedHashMap<>();
        rows.forEach(row -> {
            String key = firstText(row.parentSku(), row.sku(), row.itemId(), "未映射");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        });
        return groups.entrySet().stream()
                .map(entry -> {
                    double requested = entry.getValue().stream().mapToDouble(SkuRow::requestedUnits).sum();
                    double completed = entry.getValue().stream().mapToDouble(SkuRow::completedUnits).sum();
                    double sold = entry.getValue().stream().mapToDouble(SkuRow::soldUnits).sum();
                    return new ParentBreakdown(
                            entry.getKey(),
                            round(requested),
                            round(completed),
                            round(sold),
                            dimensionRows(entry.getValue(), true),
                            dimensionRows(entry.getValue(), false)
                    );
                })
                .filter(item -> item.completedUnits() > 0)
                .sorted(Comparator.comparingDouble(ParentBreakdown::completedUnits).reversed()
                        .thenComparing(ParentBreakdown::parentSku))
                .toList();
    }

    private static List<BreakdownRow> breakdown(
            List<DetailRow> rows,
            java.util.function.Function<DetailRow, String> classifier
    ) {
        Map<String, BreakdownAccumulator> groups = new LinkedHashMap<>();
        rows.forEach(row -> {
            String label = classifier.apply(row);
            label = label == null || label.isBlank() ? "未填写" : label.trim();
            groups.computeIfAbsent(label, BreakdownAccumulator::new).add(row.quantity(), row.totalRefundAmount());
        });
        double total = groups.values().stream().mapToDouble(BreakdownAccumulator::units).sum();
        return groups.values().stream()
                .map(item -> item.row(total))
                .sorted(Comparator.comparingDouble(BreakdownRow::units).reversed()
                        .thenComparing(BreakdownRow::label))
                .toList();
    }

    private static YearMonth parseMonth(String value, YearMonth fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("月份格式应为 YYYY-MM");
        }
    }

    private static ReturnPayload emptyPayload() {
        return new ReturnPayload(
                new Meta(null, null, null, null, null, null, List.of(), "USD",
                        "退款件数 ÷ 全量订单销售件数(已导入所有月份, 去重后)", "Return Invoice Status = RETURN_INVOICED"),
                new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new AccountSummary(null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of()
        );
    }

    private static double ratio(double numerator, double denominator) {
        return Math.abs(denominator) < 0.000001 ? 0 : round(numerator / denominator);
    }

    static String refundRisk(double completedUnits, double soldUnits) {
        if (soldUnits <= 0) {
            return "NO_SALES_DENOMINATOR";
        }
        double completedRate = ratio(completedUnits, soldUnits);
        return completedRate >= 0.15 ? "HIGH"
                : completedRate >= 0.08 ? "WATCH" : "NORMAL";
    }

    private static int refundRiskPriority(String risk) {
        return switch (risk) {
            case "HIGH" -> 3;
            case "WATCH" -> 2;
            case "NORMAL" -> 1;
            default -> 0;
        };
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return new BigDecimal(String.valueOf(value)).doubleValue();
    }

    private static long longNumber(Map<String, Object> row, String key) {
        return Math.round(number(row, key));
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && ("true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value)));
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    public record ReturnPayload(
            Meta meta,
            Summary summary,
            AccountSummary accountSummary,
            List<MonthlyRow> trend,
            List<ParentMonthlyRow> parentTrend,
            List<ParentRow> parentRows,
            List<SkuRow> skuRows,
            List<ParentBreakdown> parentBreakdowns,
            List<BreakdownRow> reasons,
            List<BreakdownRow> statuses,
            List<BreakdownRow> channels,
            List<BreakdownRow> refundModes,
            List<BreakdownRow> coveredBy,
            List<DetailRow> details,
            Map<String, BigDecimal> manualRefundRates
    ) {
    }

    public record Meta(
            String firstMonth,
            String latestMonth,
            String latestReportMonth,
            String selectedMonth,
            LocalDate startDate,
            LocalDate endDate,
            List<String> reportMonths,
            String currency,
            String rateDefinition,
            String completedDefinition
    ) {
    }

    public record Summary(
            double requestedUnits,
            double completedUnits,
            double cancelledUnits,
            double pendingUnits,
            double soldUnits,
            double requestRate,
            double completedRate,
            double refundAmount,
            double salesRevenue,
            double refundAmountRate,
            double sellerAmount,
            double walmartAmount,
            double keepItUnits,
            long returnLines,
            long affectedSkus,
            long affectedParents,
            double mappingRate
    ) {
    }

    public record AccountSummary(
            String firstMonth,
            String lastMonth,
            int monthCount,
            double requestedUnits,
            double completedUnits,
            double soldUnits,
            double requestRate,
            double completedRate,
            double refundAmount,
            double salesRevenue,
            double refundAmountRate
    ) {
    }

    public record MonthlyRow(
            String month,
            double requestedUnits,
            double completedUnits,
            double cancelledUnits,
            double pendingUnits,
            double soldUnits,
            double requestRate,
            double completedRate,
            double refundAmount,
            double salesRevenue,
            double refundAmountRate
    ) {
    }

    public record ParentMonthlyRow(
            String parentSku,
            String itemName,
            String month,
            double requestedUnits,
            double completedUnits,
            double cancelledUnits,
            double pendingUnits,
            double soldUnits,
            double requestRate,
            double completedRate,
            double refundAmount,
            double salesRevenue,
            double refundAmountRate
    ) {
    }

    public record ParentTotal(
            String parentSku,
            double completedUnits,
            double soldUnits,
            double completedRate
    ) {
    }

    public record SkuRow(
            String groupKey,
            String sku,
            String parentSku,
            String itemId,
            String itemName,
            String imageUrl,
            String color,
            String size,
            double requestedUnits,
            double completedUnits,
            double cancelledUnits,
            double pendingUnits,
            double soldUnits,
            double requestRate,
            double completedRate,
            double refundAmount,
            double salesRevenue,
            double refundAmountRate,
            String reasonSummary,
            String risk,
            double totalCompletedUnits,
            double totalSoldUnits,
            double totalCompletedRate,
            String totalRisk
    ) {
    }

    public record ParentRow(
            String parentSku,
            String itemName,
            String imageUrl,
            int skuCount,
            int colorCount,
            int sizeCount,
            double requestedUnits,
            double completedUnits,
            double cancelledUnits,
            double pendingUnits,
            double soldUnits,
            double requestRate,
            double completedRate,
            double refundAmount,
            double salesRevenue,
            double refundAmountRate,
            double totalCompletedUnits,
            double totalSoldUnits,
            double totalCompletedRate,
            String risk,
            String totalRisk
    ) {
    }

    public record DimensionRow(
            String label,
            int skuCount,
            double requestedUnits,
            double completedUnits,
            double cancelledUnits,
            double pendingUnits,
            double soldUnits,
            double requestRate,
            double completedRate,
            double refundAmount,
            double salesRevenue,
            double refundAmountRate,
            double shareOfParentCompletedRefunds,
            String risk,
            double totalCompletedUnits,
            double totalSoldUnits,
            double totalCompletedRate,
            String totalRisk
    ) {
    }

    public record SkuTotal(
            String groupKey,
            double completedUnits,
            double soldUnits,
            double completedRate,
            String risk
    ) {
    }

    public record ParentBreakdown(
            String parentSku,
            double requestedUnits,
            double completedUnits,
            double soldUnits,
            List<DimensionRow> colors,
            List<DimensionRow> sizes
    ) {
    }

    public record BreakdownRow(
            String label,
            double units,
            double amount,
            double share
    ) {
    }

    public record DetailRow(
            LocalDateTime requestAt,
            LocalDate reportMonth,
            String customerOrderNo,
            String poNo,
            String poLineNo,
            String rmaNo,
            String returnOrderLineNo,
            double quantity,
            String returnReason,
            String itemId,
            String sku,
            String parentSku,
            String itemName,
            String imageUrl,
            String color,
            String size,
            String condition,
            String currentStatus,
            LocalDateTime currentStatusTime,
            boolean keepIt,
            String carrier,
            String trackingNumber,
            String channel,
            String customerComments,
            double orderAmount,
            double returnShippingFee,
            double restockingFee,
            double totalRefundAmount,
            String sellerOrderNo,
            String refundMode,
            String refundCoveredBy,
            String trackingStatus,
            String refundStatus,
            boolean replacement,
            String platformType,
            String returnShipFrom,
            String returnShipTo
    ) {
    }

    private record Bounds(
            YearMonth firstMonth,
            YearMonth latestMonth,
            YearMonth latestReportMonth,
            List<String> reportMonths,
            YearMonth reportStartMonth,
            YearMonth reportEndMonth
    ) {
    }

    private static final class MonthlyAccumulator {
        private final String month;
        private double requested;
        private double completed;
        private double cancelled;
        private double pending;
        private double refundAmount;
        private double sold;
        private double salesRevenue;

        private MonthlyAccumulator(String month) {
            this.month = month;
        }

        private MonthlyAccumulator(
                String month,
                double requested,
                double completed,
                double cancelled,
                double pending,
                double refundAmount
        ) {
            this.month = month;
            this.requested = requested;
            this.completed = completed;
            this.cancelled = cancelled;
            this.pending = pending;
            this.refundAmount = refundAmount;
        }

        private void sales(double sold, double salesRevenue) {
            this.sold = sold;
            this.salesRevenue = salesRevenue;
        }

        private String month() {
            return month;
        }

        private double sold() {
            return sold;
        }

        private double salesRevenue() {
            return salesRevenue;
        }

        private MonthlyRow row() {
            return new MonthlyRow(
                    month,
                    round(requested),
                    round(completed),
                    round(cancelled),
                    round(pending),
                    round(sold),
                    ratio(requested, sold),
                    ratio(completed, sold),
                    round(refundAmount),
                    round(salesRevenue),
                    ratio(refundAmount, salesRevenue)
            );
        }
    }

    private static final class ParentAccumulator {
        private final String parentSku;
        private String itemName;
        private String imageUrl;
        private final Set<String> skus = new LinkedHashSet<>();
        private final Set<String> colors = new LinkedHashSet<>();
        private final Set<String> sizes = new LinkedHashSet<>();
        private double requested;
        private double completed;
        private double cancelled;
        private double pending;
        private double sold;
        private double refundAmount;
        private double salesRevenue;

        private ParentAccumulator(String parentSku) {
            this.parentSku = parentSku;
        }

        private void add(SkuRow row) {
            itemName = firstText(itemName, row.itemName());
            imageUrl = firstText(imageUrl, row.imageUrl());
            skus.add(firstText(row.sku(), row.itemId(), row.groupKey()));
            if (row.color() != null && !row.color().isBlank()) {
                colors.add(row.color());
            }
            if (row.size() != null && !row.size().isBlank()) {
                sizes.add(row.size());
            }
            requested += row.requestedUnits();
            completed += row.completedUnits();
            cancelled += row.cancelledUnits();
            pending += row.pendingUnits();
            sold += row.soldUnits();
            refundAmount += row.refundAmount();
            salesRevenue += row.salesRevenue();
        }

        private ParentRow row(ParentTotal total) {
            double requestRate = ratio(requested, sold);
            double completedRate = ratio(completed, sold);
            String totalRisk = total == null
                    ? "NO_SALES_DENOMINATOR"
                    : refundRisk(total.completedUnits(), total.soldUnits());
            return new ParentRow(
                    parentSku,
                    firstText(itemName, parentSku),
                    imageUrl,
                    skus.size(),
                    colors.size(),
                    sizes.size(),
                    round(requested),
                    round(completed),
                    round(cancelled),
                    round(pending),
                    round(sold),
                    requestRate,
                    completedRate,
                    round(refundAmount),
                    round(salesRevenue),
                    ratio(refundAmount, salesRevenue),
                    total == null ? 0 : total.completedUnits(),
                    total == null ? 0 : total.soldUnits(),
                    total == null ? 0 : total.completedRate(),
                    refundRisk(completed, sold),
                    totalRisk
            );
        }
    }

    private static final class DimensionAccumulator {
        private final String label;
        private final Set<String> skus = new LinkedHashSet<>();
        private double requested;
        private double completed;
        private double cancelled;
        private double pending;
        private double sold;
        private double refundAmount;
        private double salesRevenue;
        private double totalCompleted;
        private double totalSold;

        private DimensionAccumulator(String label) {
            this.label = label;
        }

        private void add(SkuRow row) {
            skus.add(firstText(row.sku(), row.itemId(), row.groupKey()));
            requested += row.requestedUnits();
            completed += row.completedUnits();
            cancelled += row.cancelledUnits();
            pending += row.pendingUnits();
            sold += row.soldUnits();
            refundAmount += row.refundAmount();
            salesRevenue += row.salesRevenue();
            totalCompleted += row.totalCompletedUnits();
            totalSold += row.totalSoldUnits();
        }

        private DimensionRow row(double parentCompleted) {
            double requestRate = ratio(requested, sold);
            double completedRate = ratio(completed, sold);
            return new DimensionRow(
                    label,
                    skus.size(),
                    round(requested),
                    round(completed),
                    round(cancelled),
                    round(pending),
                    round(sold),
                    requestRate,
                    completedRate,
                    round(refundAmount),
                    round(salesRevenue),
                    ratio(refundAmount, salesRevenue),
                    ratio(completed, parentCompleted),
                    refundRisk(completed, sold),
                    round(totalCompleted),
                    round(totalSold),
                    ratio(totalCompleted, totalSold),
                    refundRisk(totalCompleted, totalSold)
            );
        }
    }

    private static final class BreakdownAccumulator {
        private final String label;
        private double units;
        private double amount;

        private BreakdownAccumulator(String label) {
            this.label = label;
        }

        private void add(double units, double amount) {
            this.units += units;
            this.amount += amount;
        }

        private double units() {
            return units;
        }

        private BreakdownRow row(double total) {
            return new BreakdownRow(label, round(units), round(amount), ratio(units, total));
        }
    }
}
