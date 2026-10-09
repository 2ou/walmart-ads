package com.ai.walmartads.product.application;

import com.ai.walmartads.returns.application.ReturnQuerySupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依据 RETURN_ORDER 历史数据，按"对应款(parent)"推算平均退款率。
 * 口径复用 ReturnAnalyticsService：退款率 = 该款 RETURN_INVOICED 件数 ÷ 退款文件时间范围内的同款订单销售件数。
 * 仅返回存在历史数据的款；该数据在产品库仅供参考，不会覆盖手工退款率。
 *
 * <p><b>性能</b>：该查询要扫 {@code wm_ads_order_record_valid}（视图内嵌套 product_record 相关子查询）
 * 与 {@code wm_ads_return_order} 的窗口排名，实测单次约 <b>300ms</b>；而产品库每次打开都会调用它。
 * 故此处按「数据指纹」缓存结果：指纹由退货 / 订单 / 导入文件 / SKU 映射的规模与生效数拼成
 * （实测约 <b>13ms</b>），任一数据源新增或生效状态变化即自动失效重算；另加 10 分钟兜底 TTL，
 * 避免任何未覆盖的变更路径导致长期陈旧。
 */
@Service
public class RefundRateService {

    /** 兜底有效期：即便指纹漏掉某个变更点，也会在此时长后强制重算。 */
    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(10).toNanos();

    private final NamedParameterJdbcTemplate jdbc;

    /** 数据指纹 + 结果快照。指纹变化或超时即重算；volatile 保证可见性，计算竞态最多多算一次。 */
    private volatile Snapshot snapshot;

    public RefundRateService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, BigDecimal> ratesByParent() {
        String fingerprint = fingerprint();
        long now = System.nanoTime();
        Snapshot current = snapshot;
        if (current != null
                && current.fingerprint().equals(fingerprint)
                && current.expiresAtNanos() > now) {
            return current.rates();
        }
        Map<String, BigDecimal> rates = computeRates();
        snapshot = new Snapshot(fingerprint, rates, now + CACHE_TTL_NANOS);
        return rates;
    }

    /** 供数据变更路径显式失效（非必需 —— 指纹机制已覆盖导入与同步写入）。 */
    public void invalidate() {
        snapshot = null;
    }

    /**
     * 依赖数据的「廉价指纹」。覆盖本查询用到的全部数据源：
     * 退货明细、订单明细、导入文件（含 active 生效数）、SKU 映射（含 current_version 生效数），
     * 以及产品库（退款率分母走视图 {@code wm_ads_order_record_valid}，其口径取决于
     * {@code wm_ads_product_record.current_version=1}）。任一新增或生效状态变化都会改变指纹，自动触发重算。
     */
    private String fingerprint() {
        return jdbc.queryForObject("""
                select concat_ws('|',
                           (select coalesce(max(id), 0) from wm_ads_order_record),
                           (select count(*) from wm_ads_order_record),
                           (select coalesce(max(id), 0) from wm_ads_return_order),
                           (select count(*) from wm_ads_return_order),
                           (select coalesce(max(id), 0) from wm_ads_import_file),
                           (select coalesce(sum(case when active then 1 else 0 end), 0) from wm_ads_import_file),
                           (select count(*) from wm_ads_sku_mapping),
                           (select count(*) from wm_ads_sku_mapping where current_version = true),
                           (select coalesce(max(id), 0) from wm_ads_product_record),
                           (select count(*) from wm_ads_product_record where current_version = true)
                       )
                """, new MapSqlParameterSource(), String.class);
    }

    private Map<String, BigDecimal> computeRates() {
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

    private record Snapshot(String fingerprint, Map<String, BigDecimal> rates, long expiresAtNanos) {
    }
}
