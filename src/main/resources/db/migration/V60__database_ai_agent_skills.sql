CREATE TABLE wm_ads_ai_skill (
    skill_code              VARCHAR(80)     NOT NULL,
    skill_name              VARCHAR(120)    NOT NULL,
    description             VARCHAR(500)    NOT NULL,
    instructions            LONGTEXT        NOT NULL,
    output_schema           JSON,
    version                 INT             NOT NULL DEFAULT 1,
    enabled                 TINYINT(1)      NOT NULL DEFAULT 1,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (skill_code),
    INDEX idx_ai_skill_enabled (enabled, skill_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE wm_ads_ai_skill_tool (
    skill_code              VARCHAR(80)     NOT NULL,
    tool_code               VARCHAR(100)    NOT NULL,
    sort_order              INT             NOT NULL DEFAULT 0,
    enabled                 TINYINT(1)      NOT NULL DEFAULT 1,
    PRIMARY KEY (skill_code, tool_code),
    CONSTRAINT fk_ai_skill_tool_skill FOREIGN KEY (skill_code)
        REFERENCES wm_ads_ai_skill (skill_code) ON DELETE CASCADE,
    INDEX idx_ai_skill_tool_enabled (skill_code, enabled, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE wm_ads_ai_agent_run (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    requested_skill         VARCHAR(80),
    activated_skill         VARCHAR(80),
    skill_version           INT,
    question                TEXT            NOT NULL,
    scope_payload           JSON            NOT NULL,
    tool_trace              JSON,
    response_payload        JSON,
    provider                VARCHAR(80)     NOT NULL DEFAULT 'kie',
    model_name              VARCHAR(120),
    status                  VARCHAR(20)     NOT NULL,
    prompt_tokens           INT             NOT NULL DEFAULT 0,
    completion_tokens       INT             NOT NULL DEFAULT 0,
    total_tokens            INT             NOT NULL DEFAULT 0,
    error_message           TEXT,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at            DATETIME,
    PRIMARY KEY (id),
    INDEX idx_ai_agent_run_created (created_at),
    INDEX idx_ai_agent_run_status (status, created_at),
    INDEX idx_ai_agent_run_skill (activated_skill, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO wm_ads_ai_skill
    (skill_code, skill_name, description, instructions, output_schema, version, enabled)
VALUES
    ('ad-overview', '广告经营总览', '综合分析广告投入、销售产出、SKU 与规则诊断结果。',
     '先查询广告经营总览，再按异常信号补充广告诊断或数据新鲜度。结论必须引用金额、ROAS、点击或订单等可核验指标，不得声称已经修改广告。',
     JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('summary', 'findings', 'actions')), 1, 1),
    ('campaign-diagnosis', '款号广告诊断', '分析款号、广告活动与投放配置的异常和优化机会。',
     '优先使用广告诊断工具；指定款号时分析该款号，否则先看汇总。区分当前投放与历史广告，样本不足时明确限制，不得直接执行调价、暂停或预算修改。',
     JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('summary', 'findings', 'actions')), 1, 1),
    ('manual-keyword-analysis', '手动广告关键词分析', '分析手动广告具体关键词、匹配方式、浪费花费和转化机会。',
     '使用手动关键词工具，重点比较关键词花费、销售、ROAS、点击、订单、匹配方式和当前状态。建议必须落到具体关键词或广告活动，不能把相关性描述为因果。',
     JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('summary', 'findings', 'actions')), 1, 1),
    ('historical-ads-review', '历史广告复盘', '汇总指定款号已经暂停或结束的广告生命周期表现。',
     '必须要求并使用 parentSku 查询历史广告。比较历史广告名称、生命周期、花费、销售、订单和 ROAS；只给复盘与重启建议，不得把历史广告当成当前在投。',
     JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('summary', 'findings', 'actions')), 1, 1),
    ('data-quality-check', '广告数据质量检查', '检查领星同步状态、数据日期覆盖和各报表层级新鲜度。',
     '以数据新鲜度工具为主，明确最新业务日期与最近同步时间的区别。发现缺失或失败时列出受影响报表层级，不能用模型猜测缺失数据。',
     JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('summary', 'findings', 'actions')), 1, 1);

INSERT INTO wm_ads_ai_skill_tool (skill_code, tool_code, sort_order, enabled) VALUES
    ('ad-overview', 'query_account_ad_overview', 10, 1),
    ('ad-overview', 'query_campaign_diagnosis', 20, 1),
    ('ad-overview', 'query_data_freshness', 30, 1),
    ('campaign-diagnosis', 'query_campaign_diagnosis', 10, 1),
    ('campaign-diagnosis', 'query_historical_ads', 20, 1),
    ('campaign-diagnosis', 'query_data_freshness', 30, 1),
    ('manual-keyword-analysis', 'query_manual_keyword_performance', 10, 1),
    ('manual-keyword-analysis', 'query_data_freshness', 20, 1),
    ('historical-ads-review', 'query_historical_ads', 10, 1),
    ('historical-ads-review', 'query_campaign_diagnosis', 20, 1),
    ('data-quality-check', 'query_data_freshness', 10, 1);
