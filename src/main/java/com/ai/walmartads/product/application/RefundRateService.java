package com.ai.walmartads.product.application;

import com.ai.walmartads.returns.application.ReturnQuerySupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依据 RETURN_ORDER 历史数据，按"对应款(parent)"推算平均退款率。
 * 口径复用 ReturnAnalyticsService：退款率 = 该款 RETURN_INVOICED 件数 ÷ 退款文件时间范围内的同款订单销售件数。
 * 仅返回存在历史数据的款；该数据在产品库仅供参考，不会覆盖手工退款率。
 */
@Service
public class RefundRateService {

    private final NamedParameterJdbcTemplate jdbc;

    public RefundRateService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, BigDecimal> ratesByParent() {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(ReturnQuerySupport.ACTIVE_RETURN_CTE + """
                , refund_period as (
                    select min(r.report_month) as start_month,
                           max(r.report_month) as end_month
                      from active_return_rows r
                ),
                return_parent as (
                    select coalesce(r.parent_sku, m.parent_sku) as parent_sku,
                           sum(case when r.current_status = 'RETURN_INVOICED'
                                    then r.quantity else 0 end) as refunded_units
                      from active_return_rows r
                      left join wm_ads_sku_mapping m on m.item_id = r.item_id and m.current_version = true
                      cross join refund_period period
                     where r.report_month between period.start_month and period.end_month
                     group by coalesce(r.parent_sku, m.parent_sku)
                )
                select rp.parent_sku as parent_sku,
                       case when coalesce(o.sold_units, 0) > 0
                            then rp.refunded_units / o.sold_units else null end as refund_rate
                  from return_parent rp
                  left join (
                      select p.parent_sku, sum(o.quantity) as sold_units
                        from wm_ads_order_record_valid o
                        join wm_ads_product_record p on p.sku = o.sku and p.current_version = true
                        join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                       cross join refund_period period
                       where o.order_date between period.start_month and last_day(period.end_month)
                       group by p.parent_sku
                  ) o on o.parent_sku = rp.parent_sku
                 where rp.parent_sku is not null
                """, new MapSqlParameterSource());
        for (Map<String, Object> row : rows) {
            Object parentValue = row.get("parent_sku");
            Object rateValue = row.get("refund_rate");
            if (parentValue == null || rateValue == null) {
                continue;
            }
            String parentSku = String.valueOf(parentValue);
            BigDecimal rate = rateValue instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(rateValue));
            result.put(parentSku, rate);
        }
        return result;
    }
}
