-- 目标库存覆盖天数改为可持久化的全局设置（原先各接口硬编码默认值 60，改完刷新即丢失）。
-- 单行单值表：id 恒为 1，前端在点「重新计算建议」时写库，其余接口读它作为默认口径。
CREATE TABLE wm_ads_forecast_setting (
    id TINYINT NOT NULL,
    target_days INT NOT NULL DEFAULT 60 COMMENT '全局默认目标库存覆盖天数（允许区间 7-180）',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '需求预测 / 库存覆盖天数全局设置';

INSERT INTO wm_ads_forecast_setting (id, target_days) VALUES (1, 60);
