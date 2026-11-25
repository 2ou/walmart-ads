const SETTINGS_KEY = "walmartWorkbenchCurrentBrowserBridge";
const SUPPLY_CREDENTIAL_KEY = "supplyChainCredential";
const TEMPLATE_PREFIX = "lingxingRequestTemplate:";
const HEARTBEAT_ALARM = "workbench-current-chrome-heartbeat";

const SOURCES = {
  SUPPLY_CHAIN: {
    origin: "https://czyxgyl.shangyiku.top",
    page: "https://czyxgyl.shangyiku.top/produce/deliverGoods"
  }
};

const syncing = new Map();
let scheduledSupplySync = null;

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
  if (current.sources?.includes("SUPPLY_CHAIN") && data?.pendingSources?.includes("SUPPLY_CHAIN")) {
    syncSupply().catch(() => {});
  }
  return data;
}

async function reportFailure(source, error) {
  try {
    await api("/failure", "POST", {
      source,
      message: error instanceof Error ? error.message : "当前 Chrome 同步失败",
      loginRequired: Boolean(error?.loginRequired),
      pageRequired: Boolean(error?.pageRequired)
    });
  } catch { /* keep the original error */ }
  const current = await settings();
  if (current) await saveSettings({ ...current, lastError: error?.message || "同步失败" });
  await refreshBadge();
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
  if (syncing.has("SUPPLY_CHAIN")) return syncing.get("SUPPLY_CHAIN");
  const task = syncSupplyOnce();
  syncing.set("SUPPLY_CHAIN", task);
  try {
    return await task;
  } finally {
    syncing.delete("SUPPLY_CHAIN");
  }
}

async function syncSupplyOnce() {
  const stored = await chrome.storage.session.get(SUPPLY_CREDENTIAL_KEY);
  const token = stored[SUPPLY_CREDENTIAL_KEY]?.token;
  if (!token) {
    const error = new Error("等待供应链登录；自动同步已暂停，请在数据中心手动登录");
    error.loginRequired = true;
    await reportFailure("SUPPLY_CHAIN", error);
    throw error;
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
    }
    // /supply-sync 已在服务端写入运行结果；只有请求根本没有到达工作台时才补报失败，避免一轮记两条。
    if (!error.status) await reportFailure("SUPPLY_CHAIN", error);
    throw error;
  }
}

async function ensurePage(sourceCode, activeForLogin, refreshExisting = false) {
  const source = SOURCES[sourceCode];
  const exact = await chrome.tabs.query({ url: `${source.origin}${new URL(source.page).pathname}*` });
  if (exact.length) {
    const tab = exact[0];
    if (refreshExisting) await chrome.tabs.reload(tab.id);
    if (activeForLogin) await chrome.tabs.update(tab.id, { active: true });
    return tab;
  }
  const tab = await chrome.tabs.create({ url: source.page, active: Boolean(activeForLogin) });
  return tab;
}

async function runCommand(command) {
  if (!command?.action) return heartbeat();
  if (command.action === "OPEN_PAGE" || command.action === "LOGIN") {
    if ((command.source || command.system) !== "SUPPLY_CHAIN") {
      throw new Error("领星数据已改由 MCP 同步，请在工作台的领星 MCP 同步计划中操作");
    }
    return ensurePage("SUPPLY_CHAIN", true, command.action === "OPEN_PAGE");
  }
  if (command.action !== "SYNC") throw new Error("未知的浏览器连接指令");
  const requested = command.source || "ALL";
  if (!["ALL", "SUPPLY_CHAIN"].includes(requested)) return [];
  const current = await settings();
  if (!current?.sources?.includes("SUPPLY_CHAIN")) return [];
  return [await syncSupply()];
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
    sources: Array.isArray(bootstrap.sources)
      ? bootstrap.sources.filter((source) => source === "SUPPLY_CHAIN") : [],
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

chrome.webRequest.onBeforeSendHeaders.addListener((details) => {
  if (details.method !== "OPTIONS") saveSupplyToken(bearer(details.requestHeaders)).catch(() => {});
}, { urls: ["https://czyxgyl.shangyiku.top/prod-api/*"] }, ["requestHeaders", "extraHeaders"]);
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) heartbeat().catch(() => {});
});

async function bootstrap() {
  // 领星数据已由 MCP 接管，清理旧版插件保留的领星请求模板。
  await chrome.storage.session.remove([
    TEMPLATE_PREFIX + "LINGXING_INVENTORY", TEMPLATE_PREFIX + "LINGXING_WFS"
  ]);
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
