-- V57: 修正 wm_ads_lx_comment_raw 的两个列类型，与实体映射对齐
--
-- 背景：V55 建表时把 comment_star / buyer_flag 定义为 TINYINT，而实体
-- com.ai.walmartads.walmartcomment.domain.WalmartComment 映射为 int / Integer
-- （JDBC INTEGER）。项目 ddl-auto=validate，启动即抛
--   Schema-validation: wrong column type encountered in column [buyer_flag]
--   in table [wm_ads_lx_comment_raw]; found [tinyint], but expecting [integer]
-- 导致 8081 无法启动。
--
-- 取舍：V55 已应用，不改历史迁移（会触发 Flyway checksum 冲突），改用新迁移把列放宽到
-- 实体声明的类型。comment_star 取值 1~5、buyer_flag 语义未知，取 INT 更稳，代价可忽略。
-- 该表此时尚无数据（V55 由本次启动首次应用），无回填风险。

ALTER TABLE wm_ads_lx_comment_raw
    MODIFY COLUMN comment_star INT NOT NULL,
    MODIFY COLUMN buyer_flag INT NULL COMMENT '领星原值，语义未在文档中明确，仅留档';
