-- Version 2.0.4 incorrectly labelled a missing Lingxing page request as a login failure.
UPDATE wm_ads_connector_source_state
   SET status = 'WAITING_PAGE',
       last_message = '尚未读取对应领星业务页面，请点击打开页面并刷新'
 WHERE source_code IN ('LINGXING_INVENTORY', 'LINGXING_WFS')
   AND status = 'LOGIN_REQUIRED'
   AND last_message LIKE '等待领星登录或刷新对应业务页面%';

-- Only undo that specific false diagnosis when a previous successful validation exists.
UPDATE wm_ads_connector_session
   SET status = 'CONNECTED', last_message = '此前已验证领星会话，请按数据源提示打开对应页面'
 WHERE system_code = 'LINGXING'
   AND status = 'LOGIN_REQUIRED'
   AND last_validated_at IS NOT NULL
   AND last_message LIKE '等待领星登录或刷新对应业务页面%';
