-- 渠道到达时效统一改为人工维护。
-- 清除历史签收自动生成且尚未经过人工确认的时效，保留 manual_override = true 的值。
UPDATE wm_ads_transit_channel_lead_time
   SET lead_days = NULL,
       updated_at = CURRENT_TIMESTAMP(6)
 WHERE manual_override = FALSE;

ALTER TABLE wm_ads_transit_channel_lead_time
    COMMENT = '渠道路途时效；渠道由物流表登记，时效天数全部由用户人工维护';
