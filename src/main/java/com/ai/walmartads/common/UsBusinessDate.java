package com.ai.walmartads.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 美国业务日（美西日历日）判定。
 *
 * <p>全系统订单 / 销量数据的日期语义是「美国日期」：领星返回的日期即美西日历日，
 * 库里 {@code wm_ads_order_record.order_date} 原样存储、不做时区换算。
 * 但服务的"当前时间"取的是服务器本机时区（Asia/Shanghai），直接用 {@code LocalDate.now()}
 * 会带来一天的错误 —— 北京时间 09-25 05:00 时，美国还是 09-24。
 *
 * <p>美西夏令时（PDT，UTC-7）与北京时间（UTC+8）相差 15 小时，因此北京时间 16:00
 * 恰好是美西新的一天开始；冬令时（PST，UTC-8）相差 16 小时，对应北京时间 17:00。
 * 这里直接用真实时区换算，夏令时/冬令时切换自动生效，不硬编码 16:00。
 *
 * <p>典型用法：订单同步窗口的右端点、"最新业务日"为空时的回落值。
 */
public final class UsBusinessDate {

    /** 服务器本机时区（系统实际运行的时区）。 */
    private static final ZoneId SERVER_ZONE = ZoneId.systemDefault();
    /** 美国销售地时区（Walmart 站点为美西）。 */
    private static final ZoneId US_ZONE = ZoneId.of("America/Los_Angeles");
    /** 经营页面统一使用北京时间，并在每天 16:00 开放当日数据。 */
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime SALES_DAY_CUTOFF = LocalTime.of(16, 0);

    private UsBusinessDate() {
    }

    /**
     * 当前美国业务日（美西日历日）。
     *
     * <p>等价于：把服务器当前时间换算到美西后取日期。北京时间 16:00 之后，
     * 该值比服务器本地日期大一天 —— 这正是"北京时间 16:00 才是美国新的一天"。
     */
    public static LocalDate usToday() {
        return LocalDateTime.now(SERVER_ZONE).atZone(SERVER_ZONE).withZoneSameInstant(US_ZONE).toLocalDate();
    }

    /**
     * 页面允许展示的最新销售日期。
     *
     * <p>北京时间 16:00 前只展示至昨日，16:00 起展示当日。该规则只约束经营页面，
     * 不替代领星同步任务使用的真实美西业务日。</p>
     */
    public static LocalDate visibleSalesDate() {
        return visibleSalesDate(Clock.systemUTC());
    }

    static LocalDate visibleSalesDate(Clock clock) {
        ZonedDateTime reportingNow = ZonedDateTime.now(clock).withZoneSameInstant(REPORTING_ZONE);
        return reportingNow.toLocalTime().isBefore(SALES_DAY_CUTOFF)
                ? reportingNow.toLocalDate().minusDays(1)
                : reportingNow.toLocalDate();
    }

    /** 将数据源最新日期限制到页面当前允许展示的销售日期。 */
    public static LocalDate capToVisibleSalesDate(LocalDate date) {
        LocalDate visibleDate = visibleSalesDate();
        return date == null || date.isAfter(visibleDate) ? visibleDate : date;
    }

    /**
     * 给定日期是否就是当前美国业务日。
     */
    public static boolean isUsToday(LocalDate date) {
        return date != null && date.isEqual(usToday());
    }

    /**
     * 该日期是否已完整走完（即：已算"昨日"而非"进行中的当日"）。
     *
     * <p>美国业务日一旦翻页，当天的数据就不再是半截状态。用于区分
     * "进行中的当日（数据会继续增长）"与"已收盘的完整日"。
     */
    public static boolean isClosedBusinessDay(LocalDate date) {
        return date != null && date.isBefore(usToday());
    }

    /**
     * 美国业务日距今的天数差（今日为 0，昨日为 1）。
     */
    public static long daysFromUsToday(LocalDate date) {
        return date == null ? Long.MAX_VALUE : usToday().until(date).getDays();
    }
}
