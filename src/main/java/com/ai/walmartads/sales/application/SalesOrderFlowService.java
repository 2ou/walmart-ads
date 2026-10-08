package com.ai.walmartads.sales.application;

import com.ai.walmartads.common.UsBusinessDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「成交流水」——订单行级明细（一条成交一行）的服务端分页查询。
 *
 * <p>销量分析页已有「销量明细」聚合表（款号/SKU 级、前端内存分页）。本服务补充的是
 * <b>行级</b>流水：直接读 {@code wm_ads_order_record_valid} 有效视图，与全站口径一致
 * （仅产品库 current_version=1 的款），并沿用 {@code wm_ads_import_file.active = true} 过滤。
 *
 * <p>行数规模：全期 13,617 行（HISTORY 9,174 + 领星 4,443），故必须服务端分页，
 * 不可整包下发。
 */
@Service
public class SalesOrderFlowService {

    private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(20, 50, 100);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final NamedParameterJdbcTemplate jdbc;

    public SalesOrderFlowService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param requestedStart 成交开始日期；为空时默认取最新成交日往前 30 天
     * @param requestedEnd   成交结束日期；为空时默认取最新成交日
     * @param keyword        模糊匹配 SKU / 款号 / 商品名；可为空
     * @param requestedPage  从 0 开始的页号
     * @param requestedPageSize 每页条数，仅接受 20 / 50 / 100，其余回落 50
     * @param sort           date | unitPrice | amount（其余回落 date）
     * @param direction      asc | desc（其余回落 desc）
     */
    public OrderFlowPayload load(LocalDate requestedStart, LocalDate requestedEnd, String keyword,
                                 int requestedPage, int requestedPageSize, String sort, String direction) {
        Map<String, Object> bounds = jdbc.queryForMap("""
                select min(o.order_date) as first_date,
                       max(o.order_date) as latest_date
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f
                    on f.id = o.import_file_id and f.active = true
                """, new MapSqlParameterSource());
        LocalDate firstDate = toLocalDate(bounds.get("first_date"));
        LocalDate latestDate = toLocalDate(bounds.get("latest_date"));
        latestDate = UsBusinessDate.capToVisibleSalesDate(latestDate);
        if (firstDate == null) {
            firstDate = latestDate;
        }

        LocalDate start = requestedStart != null ? requestedStart : latestDate.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        LocalDate end = requestedEnd != null ? UsBusinessDate.capToVisibleSalesDate(requestedEnd) : latestDate;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期。");
        }

        int pageSize = ALLOWED_PAGE_SIZES.contains(requestedPageSize) ? requestedPageSize : DEFAULT_PAGE_SIZE;
        String orderColumn = resolveOrderColumn(sort);
        String orderDir = "asc".equalsIgnoreCase(direction) ? "ASC" : "DESC";

        String trimmedKeyword = keyword == null ? "" : keyword.trim();
        boolean hasKeyword = !trimmedKeyword.isEmpty();

        StringBuilder where = new StringBuilder(" where o.order_date between :startDate and :endDate");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDate", start)
                .addValue("endDate", end);
        if (hasKeyword) {
            where.append(" and (o.sku like :kw or o.parent_sku like :kw or o.item_description like :kw)");
            params.addValue("kw", "%" + trimmedKeyword + "%");
        }
        String whereSql = where.toString();

        Long counted = jdbc.queryForObject("""
                select count(*)
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f
                    on f.id = o.import_file_id and f.active = true
                """ + whereSql, params, Long.class);
        long total = counted == null ? 0L : counted;
        int totalPages = (int) Math.max(1, Math.ceil(total / (double) pageSize));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        params.addValue("limit", pageSize).addValue("offset", (long) page * pageSize);
        List<OrderFlowRow> rows = jdbc.query("""
                select o.order_date        as order_date,
                       o.parent_sku        as parent_sku,
                       o.sku               as sku,
                       o.item_description  as item_description,
                       o.quantity          as quantity,
                       o.item_cost         as unit_price,
                       (o.quantity * o.item_cost) as amount
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f
                    on f.id = o.import_file_id and f.active = true
                """ + whereSql
                + " order by " + orderColumn + " " + orderDir + ", o.id " + orderDir
                + " limit :limit offset :offset",
                params,
                (rs, rowNum) -> new OrderFlowRow(
                        toLocalDate(rs.getDate("order_date")),
                        rs.getString("parent_sku"),
                        rs.getString("sku"),
                        rs.getString("item_description"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("amount")
                ));

        return new OrderFlowPayload(rows, total, page, pageSize, totalPages, firstDate, latestDate, start, end);
    }

    /** 排序字段白名单，杜绝 SQL 注入。 */
    private static String resolveOrderColumn(String sort) {
        String key = sort == null ? "" : sort.trim();
        return switch (key) {
            case "unitPrice" -> "o.item_cost";
            case "amount" -> "(o.quantity * o.item_cost)";
            default -> "o.order_date";
        };
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    /** 一条成交（1 件）一行。 */
    public record OrderFlowRow(
            LocalDate orderDate,
            String parentSku,
            String sku,
            String itemDescription,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount
    ) {
    }

    public record OrderFlowPayload(
            List<OrderFlowRow> rows,
            long total,
            int page,
            int pageSize,
            int totalPages,
            LocalDate firstDate,
            LocalDate latestDate,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
}
