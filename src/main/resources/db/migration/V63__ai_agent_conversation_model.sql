ALTER TABLE wm_ads_ai_conversation
    ADD COLUMN model_name VARCHAR(120) NOT NULL DEFAULT 'gpt-5-6-terra' AFTER requested_skill;
