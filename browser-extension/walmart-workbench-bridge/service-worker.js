const SETTINGS_KEY = "walmartWorkbenchCurrentBrowserBridge";
const SUPPLY_CREDENTIAL_KEY = "supplyChainCredential";
const TEMPLATE_PREFIX = "lingxingRequestTemplate:";
const HEARTBEAT_ALARM = "workbench-current-chrome-heartbeat";

const SOURCES = {
  LINGXING_INVENTORY: {
    origin: "https://czyx.lingxing.com",
    path: "/api/storage/lists",
    page: "https://czyx.lingxing.com/erp/msupply/warehouseDetail",
    endpoint: "/lingxing-inventory"
  },
  LINGXING_WFS: {
    origin: "https://czyx.lingxing.com",
    origins: ["https://czyx.lingxing.com", "https://gw.lingxingerp.com"],
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
const scheduledSyncs = new Map();
const watchedTabs = new Set();
let scheduledSupplySync = null;

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
  for (const source of (data?.pendingSources || [])) {
    if (source === "SUPPLY_CHAIN") syncSupply().catch(() => {});
    else if (SOURCES[source]) scheduleLingxingSync(source);
  }
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
      && (source.origins || [source.origin]).includes(parsed.origin)
      && parsed.pathname === source.path)?.[0] || null;
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
  return transformBody(template, (value) => ({ ...value, queryValues: [], queryValue: "",
      cargoStatusList: [], cargoLocalStatusList: [], shipStatus: null, hasShippingList: null,
      shippingListStatuses: [], diffOfDeclareAndShip: null, diffOfDeclareAndSign: null,
      diffOfSignAndDeclare: null, startTime: null, endTime: null, offset: 0, length: 500 }));
}

function transformBody(template, transform) {
  if (!template.body) throw new Error("没有捕获到领星列表请求体，请刷新对应页面后重试");
  try {
    return { ...template, body: JSON.stringify(transform(JSON.parse(template.body))) };
  } catch {
    const params = new URLSearchParams(template.body);
    if (![...params.keys()].length) throw new Error("领星列表请求体无法识别，请刷新对应页面后重试");
    return { ...template, body: new URLSearchParams(transform(Object.fromEntries(params.entries()))).toString() };
  }
}

/**
 * 国内仓库存：这个接口的「仓库明细」是所有店铺/所有仓库混装的列表（本账号全库 63.5 万行），
 * 实测 sid_list（店铺筛选）传了本店铺 store_id 也不生效，**只有 search_value 的 SKU 搜索能真正收窄范围**。
 * 另外页面「隐藏零库存」一开，缺货 SKU 会整行消失（970 → 97），等于把"没货"记成"没这个 SKU"。
 * 所以重放前统一：按店铺 SKU 前缀搜索 + 关闭隐藏零库存 + 分页归零交给 pagination 翻页。
 */
function canonicalInventory(template, skuPrefix) {
  if (!skuPrefix) throw new Error("工作台店铺缩写为空，无法限定领星库存范围");
  return transformBody(template, (value) => {
    const next = { ...value, offset: 0, length: 500, is_hide_zero_stock: 0 };
    next.search_field = "sku";
    next.search_value = skuPrefix;
    return next;
  });
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

async function fetchAll(normalized, sourceCode) {
  const paging = pagination(normalized, sourceCode);
  const first = await fetchTemplate(paging ? paging.apply(1) : normalized, sourceCode);
  const firstRows = firstArray(first);
  const total = totalOf(first);
  const limit = MAX_FETCH_ROWS[sourceCode] || MAX_FETCH_ROWS.DEFAULT;
  if (total != null && total > limit) {
    throw new Error(`领星返回总量 ${total} 行，超过单次上限 ${limit}，疑似页面筛选条件丢失（未选中本店铺），已保留原数据`);
  }
  if (!firstRows) throw new Error("领星列表响应结构无法识别，已保留原数据");
  if (!paging || paging.size <= 0) {
    if (total != null && firstRows.length < total) {
      throw new Error(`领星只读取到 ${firstRows.length}/${total} 行且无法分页，已保留原数据`);
    }
    return first;
  }
  if (total != null && firstRows.length >= total) return first;
  const rows = [...firstRows];
  const pageCount = total == null
    ? Math.min(100, Math.ceil(limit / paging.size))
    : Math.min(100, Math.ceil(total / paging.size));
  for (let page = 2; page <= pageCount; page += 1) {
    const payload = await fetchTemplate(paging.apply(page), sourceCode);
    const pageRows = firstArray(payload);
    if (!pageRows) throw new Error(`领星第 ${page} 页结构无法识别，已保留原数据`);
    rows.push(...pageRows);
    if (!pageRows.length || pageRows.length < paging.size || (total != null && rows.length >= total)) break;
    if (rows.length >= limit) throw new Error(`领星读取已达到安全上限 ${limit} 行，已保留原数据`);
  }
  if (total != null && rows.length < total) throw new Error(`领星只读取到 ${rows.length}/${total} 行，已保留原数据`);
  if (total == null && rows.length > 0 && rows.length % paging.size === 0) {
    throw new Error(`领星分页没有返回总数，读取到 ${rows.length} 行后仍未结束，已保留原数据`);
  }
  const resolvedTotal = total == null ? rows.length : total;
  return { ...first, data: { ...(first.data || {}), list: rows, total: resolvedTotal } };
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
      const normalized = canonicalTemplate(template, sourceCode, current?.storeAbbreviation || "");
      const response = await fetchAll(normalized, sourceCode);
      const result = await api(SOURCES[sourceCode].endpoint, "POST", {
        sourceUrl: normalized.url,
        response,
        capturedAt: new Date().toISOString(),
        // 重放用的真实请求体：工作台据此判断筛选条件是否被页面改动过（只做诊断，不入库）。
        requestBody: typeof normalized.body === "string" ? normalized.body.slice(0, 20000) : null
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

function scheduleLingxingSync(source) {
  const previous = scheduledSyncs.get(source);
  if (previous) clearTimeout(previous);
  const timer = setTimeout(() => {
    scheduledSyncs.delete(source);
    syncLingxing(source).catch(() => {});
  }, 800);
  scheduledSyncs.set(source, timer);
}

function bearer(headers = []) {
  const value = headers.find((header) => String(header.name).toLowerCase() === "authorization")?.value || "";
  const match = value.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : "";
}

async function fingerprint(value) {
  const bytes = new TextEncoder().encode(String(value || ""));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function scheduleSupplySync() {
  if (scheduledSupplySync) clearTimeout(scheduledSupplySync);
  scheduledSupplySync = setTimeout(() => {
    scheduledSupplySync = null;
    syncSupply().catch(() => {});
  }, 1000);
}

async function saveSupplyToken(token) {
  if (!token) return;
  const tokenHash = await fingerprint(token);
  const current = await settings();
  if (current?.rejectedSupplyTokenHash === tokenHash) return;
  const stored = await chrome.storage.session.get(SUPPLY_CREDENTIAL_KEY);
  if (stored[SUPPLY_CREDENTIAL_KEY]?.token === token) return;
  await chrome.storage.session.set({ [SUPPLY_CREDENTIAL_KEY]: { token, capturedAt: new Date().toISOString() } });
  if (current) {
    await saveSettings({ ...current, rejectedSupplyTokenHash: null, lastError: null });
  }
  try {
    await api("/credential", "POST", { value: `Bearer ${token}` });
    scheduleSupplySync();
  } catch { /* bootstrap may arrive later */ }
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
    if (error.status === 401 || error.status === 403
        || /登录|凭证|令牌|认证|无法访问系统资源/.test(error.message || "")) {
      error.loginRequired = true;
      const current = await settings();
      if (current && token) {
        await saveSettings({
          ...current,
          rejectedSupplyTokenHash: await fingerprint(token),
          lastError: error?.message || "供应链登录已失效"
        });
      }
      await chrome.storage.session.remove(SUPPLY_CREDENTIAL_KEY);
      await ensurePage("SUPPLY_CHAIN", true);
    }
    // /supply-sync 已在服务端写入运行结果；只有请求根本没有到达工作台时才补报失败，避免一轮记两条。
    if (!error.status) await reportFailure("SUPPLY_CHAIN", error);
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
  "https://czyx.lingxing.com/mp-platform-warehouse-api/api/cargo/list*",
  "https://gw.lingxingerp.com/mp-platform-warehouse-api/api/cargo/list*"
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
    .then(() => scheduleLingxingSync(source)).catch(() => {});
}, { urls: [
  "https://czyxgyl.shangyiku.top/prod-api/*",
  "https://czyx.lingxing.com/api/storage/lists*",
  "https://czyx.lingxing.com/mp-platform-warehouse-api/api/cargo/list*",
  "https://gw.lingxingerp.com/mp-platform-warehouse-api/api/cargo/list*"
] }, ["requestHeaders", "extraHeaders"]);

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (!watchedTabs.has(tabId) || !changeInfo.url) return;
  if (/\/login|login\.|passport|sso/i.test(changeInfo.url)) chrome.tabs.update(tabId, { active: true }).catch(() => {});
});
chrome.tabs.onRemoved.addListener((tabId) => watchedTabs.delete(tabId));

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) heartbeat().catch(() => {});
});

async function bootstrap() {
  // 同名 alarm 再次 create 会取消并重建；Service Worker 每次唤醒都执行 bootstrap，
  // 因此必须只补建缺失 alarm，不能把下一次心跳永远向后推。
  if (!await chrome.alarms.get(HEARTBEAT_ALARM)) {
    await chrome.alarms.create(HEARTBEAT_ALARM, { delayInMinutes: 1, periodInMinutes: 1 });
  }
  const tabs = await chrome.tabs.query({ url: ["http://localhost/*", "http://127.0.0.1/*"] });
  for (const tab of tabs) chrome.tabs.sendMessage(tab.id, { type: "REQUEST_BRIDGE_BOOTSTRAP" }).catch(() => {});
  await refreshBadge();
}

chrome.runtime.onInstalled.addListener(() => bootstrap().catch(() => {}));
chrome.runtime.onStartup.addListener(() => bootstrap().catch(() => {}));
bootstrap().catch(() => {});
