CREATE TABLE wm_ads_ai_config (
    id INT NOT NULL,
    enabled TINYINT(1) NOT NULL,
    api_key VARCHAR(1024),
    base_url VARCHAR(255) NOT NULL,
    model VARCHAR(128) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO wm_ads_ai_config (id, enabled, api_key, base_url, model) VALUES
    (1, 0, NULL, 'https://api.kie.ai', 'gemini-2.5-flash');
