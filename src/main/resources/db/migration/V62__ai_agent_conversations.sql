CREATE TABLE wm_ads_ai_conversation (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    title                   VARCHAR(160)    NOT NULL,
    requested_skill         VARCHAR(80)     NOT NULL DEFAULT 'AUTO',
    scope_payload           JSON            NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    message_count           INT             NOT NULL DEFAULT 0,
    last_message_preview    VARCHAR(500),
    last_message_at         DATETIME,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_ai_conversation_recent (status, last_message_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE wm_ads_ai_conversation_message (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    conversation_id         BIGINT          NOT NULL,
    role                    VARCHAR(16)     NOT NULL,
    content_text            MEDIUMTEXT,
    content_payload         JSON,
    run_id                  BIGINT,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS',
    error_message           TEXT,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_conversation_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES wm_ads_ai_conversation (id) ON DELETE CASCADE,
    INDEX idx_ai_conversation_message_order (conversation_id, id),
    INDEX idx_ai_conversation_message_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE wm_ads_ai_agent_run
    ADD COLUMN conversation_id BIGINT NULL AFTER id,
    ADD INDEX idx_ai_agent_run_conversation (conversation_id, created_at);
