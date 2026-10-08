package com.ai.walmartads.inventory.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 全局「目标库存覆盖天数」设置。
 *
 * <p>该参数此前只在前端内存里，刷新即回落到接口默认值 60，且各接口都硬编码了 {@code defaultValue = "60"}。
 * 现在落库到 {@code wm_ads_forecast_setting}（单行单值，id=1）：</p>
 * <ul>
 *   <li>写：前端在点「重新计算建议」时把新天数提交上来，先落库再按新口径重算。</li>
 *   <li>读：各接口在未显式传 {@code targetDays} 时，用库里的值作为默认口径。</li>
 * </ul>
 *
 * <p>读路径带内存缓存 + 异常回落：即使表缺失或数据库连接异常，库存与预测接口也不会因此失败，
 * 只会退回默认 60 天，符合「配置问题不该让业务接口 500」的约束。</p>
 */
@Service
public class ForecastSettingService {

    /** 表内主键，全表恒为这一行。 */
    private static final int ROW_ID = 1;

    public static final int DEFAULT_TARGET_DAYS = 60;
    public static final int MIN_TARGET_DAYS = 7;
    public static final int MAX_TARGET_DAYS = 180;

    private final JdbcTemplate jdbc;

    /** 读路径缓存：写操作后立即失效并重置，避免读到旧值。 */
    private volatile int cachedTargetDays = -1;

    public ForecastSettingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 解析实际使用的目标覆盖天数。
     *
     * @param targetDays 请求传入值；null 或非正数表示「未指定」，回落到库里的全局设置
     * @return 落在 7-180 区间内的天数值，永不返回 null
     */
    public int resolve(Integer targetDays) {
        if (targetDays == null || targetDays <= 0) {
            return getTargetDays();
        }
        return clamp(targetDays);
    }

    /** 读取全局目标覆盖天数；表缺失或查询异常时回落默认 60。 */
    public int getTargetDays() {
        int cached = cachedTargetDays;
        if (cached > 0) {
            return cached;
        }
        try {
            Integer stored = jdbc.queryForObject(
                    "select target_days from wm_ads_forecast_setting where id = ?", Integer.class, ROW_ID);
            int value = clamp(stored == null ? DEFAULT_TARGET_DAYS : stored);
            cachedTargetDays = value;
            return value;
        } catch (RuntimeException ex) {
            return DEFAULT_TARGET_DAYS;
        }
    }

    /**
     * 保存全局目标覆盖天数（幂等 upsert）。
     *
     * @return 实际落库的天数值（已 clamp）
     */
    public synchronized int saveTargetDays(int targetDays) {
        int value = clamp(targetDays);
        jdbc.update("insert into wm_ads_forecast_setting (id, target_days, updated_at) values (?, ?, now()) "
                        + "on duplicate key update target_days = values(target_days), updated_at = now()",
                ROW_ID, value);
        cachedTargetDays = value;
        return value;
    }

    /** 区间收敛到 7-180，与 InventoryDataService / ReplenishmentPlanCache 的钳制口径保持一致。 */
    public static int clamp(int targetDays) {
        return Math.max(MIN_TARGET_DAYS, Math.min(targetDays, MAX_TARGET_DAYS));
    }
}
