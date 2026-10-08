-- V41：运营记录重构 —— 引入「复盘任务」层（款 › 复盘任务 › 运营记录）
-- 说明：本次迁移前老数据仅 5 条运营记录 / 13 条提醒，均为 Flyway 种子与测试数据，
--       已确认可直接清空，不做任何兼容与回填。

DELETE FROM wm_ads_operation_reminder;
DELETE FROM wm_ads_operation_record;

-- ===== 1. 复盘任务表 =====
-- 一次有目的的运营实验（改价 / 调广告 / 换主图）就是一个复盘任务，
-- 任务下挂多条运营记录与多个复盘检查点，关闭时沉淀结论。
CREATE TABLE wm_ads_review_task (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_sku   VARCHAR(80)  NOT NULL,
    title        VARCHAR(240) NOT NULL,
    goal         TEXT         NULL,
    status       VARCHAR(20)  NOT NULL COMMENT 'ACTIVE | CLOSED',
    started_at   DATETIME(6)  NOT NULL,
    closed_at    DATETIME(6)  NULL,
    conclusion   TEXT         NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    INDEX idx_review_task_sku (parent_sku, status),
    INDEX idx_review_task_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== 2. 运营记录归属复盘任务 =====
-- 业务口径：每条记录都必须挂任务；列保持可空仅为结构安全，由服务层强制校验。
ALTER TABLE wm_ads_operation_record
    ADD COLUMN review_task_id BIGINT NULL AFTER parent_sku,
    ADD INDEX idx_operation_task (review_task_id, occurred_at);

-- ===== 3. 复盘检查点从「记录」改挂「任务」=====
-- 观察周期属于一次实验（任务），不属于单条动作；operation_id 随之废弃。
ALTER TABLE wm_ads_operation_reminder
    DROP COLUMN operation_id,
    ADD COLUMN review_task_id BIGINT NOT NULL AFTER id,
    ADD INDEX idx_reminder_task (review_task_id, due_date);
