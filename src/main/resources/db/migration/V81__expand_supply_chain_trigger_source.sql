-- 项目内置连接器会记录 PROJECT_CONNECTOR_LOGIN_CATCHUP / STARTUP_CATCHUP 等完整触发来源。
ALTER TABLE wm_ads_supply_chain_sync_log
    MODIFY COLUMN triggered_by VARCHAR(64) NOT NULL COMMENT '项目连接器 / 手动 / 定时触发来源';
