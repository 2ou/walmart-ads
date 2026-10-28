const SETTINGS_KEY = "walmartWorkbenchCurrentBrowserBridge";
const SUPPLY_CREDENTIAL_KEY = "supplyChainCredential";
const TEMPLATE_PREFIX = "lingxingRequestTemplate:";
const HEARTBEAT_ALARM = "workbench-current-chrome-heartbeat";
const SYNC_ALARM = "workbench-current-chrome-hourly-lingxing";
const SUPPLY_SYNC_ALARM = "workbench-current-chrome-daily-supply";

const SOURCES = {
  LINGXING_INVENTORY: {
    origin: "https://czyx.lingxing.com",
    path: "/api/storage/lists",
    page: "https://czyx.lingxing.com/erp/msupply/warehouseDetail",
    endpoint: "/lingxing-inventory"
  },
  LINGXING_WFS: {
    origin: "https://czyx.lingxing.com",
    path: "/mp-platform-warehouse-api/api/cargo/list",
    page: "https://czyx.lingxing.com/erp/mmulti/pwShipment",
    endpoint: "/lingxing-wfs-shipments"
  },
  SUPPLY_CHAIN: {
    origin: "https://czyxgyl.shangyiku.top",
    page: "https://czyxgyl.shangyiku.top/produce/deliverGoods"
  }
};

const pendingBodies = new Map();
const syncing = new Map();
const watchedTabs = new Set();

/** 单次重放的合法总量上限：超过说明页面筛选条件丢了（抓到全库），必须报错而不是硬翻页。 */
const MAX_FETCH_ROWS = { LINGXING_INVENTORY: 5000, LINGXING_WFS: 20000, DEFAULT: 20000 };

async function settings() {
  const stored = await chrome.storage.local.get(SETTINGS_KEY);
  return stored[SETTINGS_KEY] || null;
}

async function saveSettings(value) {
  await chrome.storage.local.set({ [SETTINGS_KEY]: value });
}

function extensionRequestSender(sender) {
  if (sender.id !== chrome.runtime.id) return false;
  const url = String(sender.tab?.url || sender.url || "");
  return url.startsWith("http://localhost:") || url.startsWith("http://127.0.0.1:")
    || url.startsWith(chrome.runtime.getURL(""));
}

async function api(path, method = "POST", body) {
  const current = await settings();
  if (!current?.key || !current?.baseUrl) throw new Error("工作台页面尚未完成自动连接");
  const response = await fetch(`${current.baseUrl}/api/browser-bridge${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-Bridge-Key": current.key,
      "X-Extension-Id": chrome.runtime.id
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(120000)
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || !payload.success) {
    const error = new Error(payload.message || `工作台请求失败（HTTP ${response.status}）`);
    error.status = response.status;
    throw error;
  }
  return payload.data;
}

async function heartbeat() {
  const current = await settings();
  if (!current?.key) return null;
  const data = await api("/heartbeat");
  await saveSettings({ ...current, online: true, lastSeenAt: new Date().toISOString(), lastError: null });
  await refreshBadge();
  return data;
}

function bodyFrom(details) {
  const body = details.requestBody;
  if (!body) return null;
  if (body.formData) {
    const params = new URLSearchParams();
    Object.entries(body.formData).forEach(([key, values]) => (values || []).forEach((value) => params.append(key, value)));
    return params.toString();
  }
  const chunks = body.raw || [];
  const length = chunks.reduce((total, chunk) => total + (chunk.bytes?.byteLength || 0), 0);
  if (!length) return null;
  const merged = new Uint8Array(length);
  let offset = 0;
  chunks.forEach((chunk) => {
    if (!chunk.bytes) return;
    const bytes = new Uint8Array(chunk.bytes);
    merged.set(bytes, offset);
    offset += bytes.byteLength;
  });
  return new TextDecoder().decode(merged);
}

function headersFrom(headers = []) {
  const blocked = /^(cookie|host|content-length|origin|referer|accept-encoding|connection|sec-|proxy-)/i;
  const result = {};
  headers.forEach((header) => {
    const name = String(header.name || "").trim();
    if (name && typeof header.value === "string" && !blocked.test(name)) result[name] = header.value;
  });
  return result;
}

function sourceForUrl(url) {
  try {
    const parsed = new URL(url);
    return Object.entries(SOURCES).find(([, source]) => source.path
      && parsed.origin === source.origin && parsed.pathname === source.path)?.[0] || null;
  } catch {
    return null;
  }
}

function ownRequest(details) {
  return String(details.initiator || "").startsWith(`chrome-extension://${chrome.runtime.id}`);
}

function firstArray(payload) {
  return [payload?.data?.list, payload?.data?.rows, payload?.data?.records, payload?.data?.items,
    payload?.list, payload?.rows, payload?.records, payload?.items].find(Array.isArray) || null;
}

function totalOf(payload) {
  const value = [payload?.data?.total, payload?.data?.total_count, payload?.data?.totalCount,
    payload?.total, payload?.total_count, payload?.totalCount, payload?.count]
    .map(Number).find(Number.isFinite);
  return value == null ? null : Math.max(0, value);
}

function canonicalWfs(template) {
  if (!template.body) return template;
  try {
    const value = JSON.parse(template.body);
    return { ...template, body: JSON.stringify({ ...value, queryValues: [], queryValue: "",
      cargoStatusList: [], cargoLocalStatusList: [], shipStatus: null, hasShippingList: null,
      shippingListStatuses: [], diffOfDeclareAndShip: null, diffOfDeclareAndSign: null,
      diffOfSignAndDeclare: null, startTime: null, endTime: null, offset: 0, length: 500 }) };
  } catch {
    return template;
  }
}

/**
 * 国内仓库存：这个接口的「仓库明细」是所有店铺/所有仓库混装的列表（本账号全库 63.5 万行），
 * 实测 sid_list（店铺筛选）传了本店铺 store_id 也不生效，**只有 search_value 的 SKU 搜索能真正收窄范围**。
 * 另外页面「隐藏零库存」一开，缺货 SKU 会整行消失（970 → 97），等于把"没货"记成"没这个 SKU"。
 * 所以重放前统一：按店铺 SKU 前缀搜索 + 关闭隐藏零库存 + 分页归零交给 pagination 翻页。
 */
function canonicalInventory(template, skuPrefix) {
  if (!template.body) return template;
  try {
    const value = JSON.parse(template.body);
    if (!Object.hasOwn(value, "offset") && !Object.hasOwn(value, "length")) return template;
    const next = { ...value, offset: 0, length: 500, is_hide_zero_stock: 0 };
    if (skuPrefix) {
      next.search_field = "sku";
      next.search_value = skuPrefix;
    }
    return { ...template, body: JSON.stringify(next) };
  } catch {
    return template;
  }
}

function canonicalTemplate(template, sourceCode, skuPrefix) {
  if (sourceCode === "LINGXING_WFS") return canonicalWfs(template);
  if (sourceCode === "LINGXING_INVENTORY") return canonicalInventory(template, skuPrefix);
  return template;
}

function pagination(template, sourceCode) {
  const contentType = Object.entries(template.headers || {})
    .find(([name]) => name.toLowerCase() === "content-type")?.[1] || "";
  if (template.body && (contentType.includes("json") || template.body.trim().startsWith("{"))) {
    try {
      const value = JSON.parse(template.body);
      if (Object.hasOwn(value, "offset") && Object.hasOwn(value, "length")
          && (sourceCode === "LINGXING_WFS" || sourceCode === "LINGXING_INVENTORY")) {
        const size = Number(value.length);
        return { size, apply(page) { return { ...template, body: JSON.stringify({ ...value, offset: (page - 1) * size }) }; } };
      }
      const pageKey = ["page", "page_no", "pageNo", "page_num", "pageNum", "current", "current_page"]
        .find((key) => Object.hasOwn(value, key));
      const sizeKey = ["page_size", "pageSize", "limit", "per_page", "perPage"]
        .find((key) => Object.hasOwn(value, key));
      if (pageKey && sizeKey) {
        return { size: Number(value[sizeKey]), apply(page) { return { ...template,
          body: JSON.stringify({ ...value, [pageKey]: page }) }; } };
      }
    } catch { /* form/query fallback below */ }
  }
  if (template.body) {
    const params = new URLSearchParams(template.body);
    const pageKey = ["page", "page_no", "pageNo", "page_num", "pageNum", "current", "current_page"]
      .find((key) => params.has(key));
    const sizeKey = ["page_size", "pageSize", "limit", "per_page", "perPage"].find((key) => params.has(key));
    if (pageKey && sizeKey) return { size: Number(params.get(sizeKey)), apply(page) {
      const next = new URLSearchParams(params); next.set(pageKey, String(page));
      return { ...template, body: next.toString() };
    } };
  }
  const url = new URL(template.url);
  const pageKey = ["page", "page_no", "pageNo", "page_num", "pageNum", "current", "current_page"]
    .find((key) => url.searchParams.has(key));
  const sizeKey = ["page_size", "pageSize", "limit", "per_page", "perPage"].find((key) => url.searchParams.has(key));
  if (!pageKey || !sizeKey) return null;
  return { size: Number(url.searchParams.get(sizeKey)), apply(page) {
    const next = new URL(template.url); next.searchParams.set(pageKey, String(page));
    return { ...template, url: next.toString() };
  } };
}

async function fetchTemplate(template, sourceCode) {
  const response = await fetch(template.url, {
    method: template.method,
    headers: template.headers,
    body: ["GET", "HEAD"].includes(template.method) ? undefined : template.body,
    credentials: "include",
    cache: "no-store",
    signal: AbortSignal.timeout(60000)
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload == null || (sourceCode === "LINGXING_WFS" && Number(payload.code) !== 1)) {
    const error = new Error(`领星读取失败（HTTP ${response.status}）`);
    error.loginRequired = response.status === 401 || response.status === 403;
    throw error;
  }
  return payload;
}

async function fetchAll(template, sourceCode, skuPrefix = "") {
  const normalized = canonicalTemplate(template, sourceCode, skuPrefix);
  const paging = pagination(normalized, sourceCode);
  const first = await fetchTemplate(paging ? paging.apply(1) : normalized, sourceCode);
  const firstRows = firstArray(first);
  const total = totalOf(first);
  const limit = MAX_FETCH_ROWS[sourceCode] || MAX_FETCH_ROWS.DEFAULT;
  if (total != null && total > limit) {
    throw new Error(`领星返回总量 ${total} 行，超过单次上限 ${limit}，疑似页面筛选条件丢失（未选中本店铺），已保留原数据`);
  }
  if (!firstRows || total == null || firstRows.length >= total || !paging || paging.size <= 0) return first;
  const rows = [...firstRows];
  const pageCount = Math.min(100, Math.ceil(total / paging.size));
  for (let page = 2; page <= pageCount; page += 1) {
    const payload = await fetchTemplate(paging.apply(page), sourceCode);
    const pageRows = firstArray(payload);
    if (!pageRows) throw new Error(`领星第 ${page} 页结构无法识别，已保留原数据`);
    rows.push(...pageRows);
    if (!pageRows.length || rows.length >= total) break;
  }
  if (rows.length < total) throw new Error(`领星只读取到 ${rows.length}/${total} 行，已保留原数据`);
  return { ...first, data: { ...(first.data || {}), list: rows, total } };
}

async function reportFailure(source, error) {
  try {
    await api("/failure", "POST", {
      source,
      message: error instanceof Error ? error.message : "当前 Chrome 同步失败",
      loginRequired: Boolean(error?.loginRequired)
    });
  } catch { /* keep the original error */ }
  const current = await settings();
  if (current) await saveSettings({ ...current, lastError: error?.message || "同步失败" });
  await refreshBadge();
}

async function syncLingxing(sourceCode) {
  if (syncing.has(sourceCode)) return syncing.get(sourceCode);
  const task = (async () => {
    const stored = await chrome.storage.session.get(TEMPLATE_PREFIX + sourceCode);
    const template = stored[TEMPLATE_PREFIX + sourceCode];
    if (!template) {
      await ensurePage(sourceCode, false);
      return { waitingForPage: true };
    }
    try {
      const current = await settings();
      const response = await fetchAll(template, sourceCode, current?.storeAbbreviation || "");
      const result = await api(SOURCES[sourceCode].endpoint, "POST", {
        sourceUrl: template.url,
        response,
        capturedAt: new Date().toISOString(),
        // 重放用的真实请求体：工作台据此判断筛选条件是否被页面改动过（只做诊断，不入库）。
        requestBody: typeof template.body === "string" ? template.body.slice(0, 20000) : null
      });
      if (current) await saveSettings({ ...current, lastSyncAt: new Date().toISOString(), lastError: null });
      return result;
    } catch (error) {
      await reportFailure(sourceCode, error);
      if (error?.loginRequired) await ensurePage(sourceCode, true);
      throw error;
    } finally {
      syncing.delete(sourceCode);
      await refreshBadge();
    }
  })();
  syncing.set(sourceCode, task);
  return task;
}

function bearer(headers = []) {
  const value = headers.find((header) => String(header.name).toLowerCase() === "authorization")?.value || "";
  const match = value.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : "";
}

async function saveSupplyToken(token) {
  if (!token) return;
  const stored = await chrome.storage.session.get(SUPPLY_CREDENTIAL_KEY);
  if (stored[SUPPLY_CREDENTIAL_KEY]?.token === token) return;
  await chrome.storage.session.set({ [SUPPLY_CREDENTIAL_KEY]: { token, capturedAt: new Date().toISOString() } });
  try { await api("/credential", "POST", { value: `Bearer ${token}` }); } catch { /* bootstrap may arrive later */ }
}

async function syncSupply() {
  const stored = await chrome.storage.session.get(SUPPLY_CREDENTIAL_KEY);
  const token = stored[SUPPLY_CREDENTIAL_KEY]?.token;
  if (!token) {
    await ensurePage("SUPPLY_CHAIN", false);
    return { waitingForPage: true };
  }
  try {
    await api("/credential", "POST", { value: `Bearer ${token}` });
    const result = await api("/supply-sync", "POST");
    const current = await settings();
    if (current) await saveSettings({ ...current, lastSupplySyncAt: new Date().toISOString(), lastError: null });
    return result;
  } catch (error) {
    if (error.status === 401 || error.status === 403 || /登录|凭证|令牌/.test(error.message || "")) {
      error.loginRequired = true;
      await ensurePage("SUPPLY_CHAIN", true);
    }
    await reportFailure("SUPPLY_CHAIN", error);
    throw error;
  }
}

async function ensurePage(sourceCode, activeForLogin) {
  const source = SOURCES[sourceCode];
  const exact = await chrome.tabs.query({ url: `${source.origin}${new URL(source.page).pathname}*` });
  if (exact.length) {
    const tab = exact[0];
    watchedTabs.add(tab.id);
    await chrome.tabs.reload(tab.id);
    if (activeForLogin) await chrome.tabs.update(tab.id, { active: true });
    return tab;
  }
  const tab = await chrome.tabs.create({ url: source.page, active: Boolean(activeForLogin) });
  watchedTabs.add(tab.id);
  return tab;
}

async function runCommand(command) {
  if (!command?.action) return heartbeat();
  if (command.action === "LOGIN") {
    if (command.system === "SUPPLY_CHAIN") {
      const stored = await chrome.storage.session.get(SUPPLY_CREDENTIAL_KEY);
      if (stored[SUPPLY_CREDENTIAL_KEY]?.token) return syncSupply();
      return ensurePage("SUPPLY_CHAIN", true);
    }
    const stored = await chrome.storage.session.get([
      TEMPLATE_PREFIX + "LINGXING_INVENTORY", TEMPLATE_PREFIX + "LINGXING_WFS"
    ]);
    if (stored[TEMPLATE_PREFIX + "LINGXING_INVENTORY"] || stored[TEMPLATE_PREFIX + "LINGXING_WFS"]) {
      const results = [];
      if (stored[TEMPLATE_PREFIX + "LINGXING_INVENTORY"]) results.push(await syncLingxing("LINGXING_INVENTORY"));
      if (stored[TEMPLATE_PREFIX + "LINGXING_WFS"]) results.push(await syncLingxing("LINGXING_WFS"));
      return results;
    }
    return ensurePage("LINGXING_INVENTORY", true);
  }
  if (command.action !== "SYNC") throw new Error("未知的浏览器连接指令");
  const requested = command.source || "ALL";
  const current = await settings();
  const targets = requested === "ALL"
    ? (current?.sources || ["LINGXING_INVENTORY", "LINGXING_WFS"]) : [requested];
  const results = [];
  for (const source of targets) {
    if (!SOURCES[source]) continue;
    results.push(source === "SUPPLY_CHAIN" ? await syncSupply() : await syncLingxing(source));
  }
  return results;
}

async function acceptWorkbenchSession(message) {
  const bootstrap = message.bootstrap || {};
  if (!bootstrap.key || !bootstrap.workspaceId) throw new Error("工作台未返回店铺连接令牌");
  const previous = await settings();
  await saveSettings({
    ...(previous || {}),
    baseUrl: String(message.baseUrl || "").replace(/\/$/, ""),
    key: bootstrap.key,
    workspaceId: bootstrap.workspaceId,
    storeName: bootstrap.storeName || "",
    storeAbbreviation: bootstrap.storeAbbreviation || "",
    sources: Array.isArray(bootstrap.sources) ? bootstrap.sources : [],
    connectedAt: new Date().toISOString(),
    lastError: null
  });
  await heartbeat();
  return runCommand(message.command);
}

async function refreshBadge() {
  const current = await settings();
  const healthy = current?.key && !current.lastError;
  await chrome.action.setBadgeBackgroundColor({ color: healthy ? "#177245" : "#b45309" });
  await chrome.action.setBadgeText({ text: current?.key ? (healthy ? "✓" : "!") : "" });
}

chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (!extensionRequestSender(sender)) {
    reply({ ok: false, error: "消息来源未获准" });
    return false;
  }
  let action;
  if (message?.type === "WORKBENCH_BRIDGE_SESSION") action = acceptWorkbenchSession(message);
  else if (message?.type === "POPUP_INFO") action = settings();
  else if (message?.type === "POPUP_SYNC") action = runCommand({ action: "SYNC", source: message.source || "ALL" });
  else return false;
  Promise.resolve(action).then((data) => reply({ ok: true, data })).catch((error) => reply({
    ok: false,
    error: error instanceof Error ? error.message : "当前 Chrome 数据连接失败"
  }));
  return true;
});

chrome.webRequest.onBeforeRequest.addListener((details) => {
  const source = sourceForUrl(details.url);
  if (!source || ownRequest(details) || details.method === "OPTIONS") return;
  pendingBodies.set(details.requestId, bodyFrom(details));
}, { urls: [
  "https://czyx.lingxing.com/api/storage/lists*",
  "https://czyx.lingxing.com/mp-platform-warehouse-api/api/cargo/list*"
] }, ["requestBody"]);

chrome.webRequest.onBeforeSendHeaders.addListener((details) => {
  if (details.url.startsWith("https://czyxgyl.shangyiku.top/prod-api/") && details.method !== "OPTIONS") {
    saveSupplyToken(bearer(details.requestHeaders)).catch(() => {});
    return;
  }
  const source = sourceForUrl(details.url);
  if (!source || ownRequest(details) || details.method === "OPTIONS") return;
  const template = {
    url: details.url,
    method: details.method || "POST",
    headers: headersFrom(details.requestHeaders),
    body: pendingBodies.get(details.requestId) || null,
    capturedAt: new Date().toISOString()
  };
  pendingBodies.delete(details.requestId);
  chrome.storage.session.set({ [TEMPLATE_PREFIX + source]: template })
    .then(() => syncLingxing(source)).catch(() => {});
}, { urls: [
  "https://czyxgyl.shangyiku.top/prod-api/*",
  "https://czyx.lingxing.com/api/storage/lists*",
  "https://czyx.lingxing.com/mp-platform-warehouse-api/api/cargo/list*"
] }, ["requestHeaders", "extraHeaders"]);

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (!watchedTabs.has(tabId) || !changeInfo.url) return;
  if (/\/login|login\.|passport|sso/i.test(changeInfo.url)) chrome.tabs.update(tabId, { active: true }).catch(() => {});
});
chrome.tabs.onRemoved.addListener((tabId) => watchedTabs.delete(tabId));

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) heartbeat().catch(() => {});
  if (alarm.name === SYNC_ALARM) {
    syncLingxing("LINGXING_INVENTORY").catch(() => {});
    syncLingxing("LINGXING_WFS").catch(() => {});
  }
  if (alarm.name === SUPPLY_SYNC_ALARM) syncSupply().catch(() => {});
});

function minutesUntilNextEight() {
  const now = new Date();
  const next = new Date(now);
  next.setHours(8, 0, 0, 0);
  if (next <= now) next.setDate(next.getDate() + 1);
  return Math.max(1, Math.ceil((next.getTime() - now.getTime()) / 60000));
}

async function bootstrap() {
  await chrome.alarms.create(HEARTBEAT_ALARM, { delayInMinutes: 1, periodInMinutes: 1 });
  await chrome.alarms.create(SYNC_ALARM, { delayInMinutes: 5, periodInMinutes: 60 });
  await chrome.alarms.create(SUPPLY_SYNC_ALARM, { delayInMinutes: minutesUntilNextEight(), periodInMinutes: 1440 });
  const tabs = await chrome.tabs.query({ url: ["http://localhost/*", "http://127.0.0.1/*"] });
  for (const tab of tabs) chrome.tabs.sendMessage(tab.id, { type: "REQUEST_BRIDGE_BOOTSTRAP" }).catch(() => {});
  const current = await settings();
  const lastSupplyDay = current?.lastSupplySyncAt ? new Date(current.lastSupplySyncAt).toDateString() : "";
  if (current?.key && new Date().getHours() >= 8 && lastSupplyDay !== new Date().toDateString()) {
    syncSupply().catch(() => {});
  }
  await refreshBadge();
}

chrome.runtime.onInstalled.addListener(() => bootstrap().catch(() => {}));
chrome.runtime.onStartup.addListener(() => bootstrap().catch(() => {}));
bootstrap().catch(() => {});
