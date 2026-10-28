const send = (message) => chrome.runtime.sendMessage(message);
const message = document.querySelector("#message");

async function render() {
  const result = await send({ type: "POPUP_INFO" });
  const info = result?.data || {};
  const connected = Boolean(info.key);
  document.querySelector("#dot").classList.toggle("ok", connected && !info.lastError);
  document.querySelector("#state").textContent = connected ? (info.lastError ? "连接需处理" : "已连接") : "等待工作台";
  document.querySelector("#store").textContent = connected
    ? `${info.storeName || info.storeAbbreviation || "当前店铺"} · ${info.baseUrl}`
    : "刷新已登录的沃尔玛工作台页面即可自动连接";
  document.querySelectorAll("[data-source],#syncAll").forEach((button) => { button.disabled = !connected; });
  if (info.lastError) message.textContent = info.lastError;
}

async function sync(source) {
  message.textContent = "正在调用当前 Chrome 登录会话…";
  const result = await send({ type: "POPUP_SYNC", source });
  message.textContent = result?.ok ? "已触发同步；需要登录时会自动打开标签页。" : (result?.error || "同步失败");
  await render();
}

document.querySelector("#syncAll").addEventListener("click", () => sync("ALL"));
document.querySelectorAll("[data-source]").forEach((button) => button.addEventListener("click", () => sync(button.dataset.source)));
document.querySelector("#openWorkbench").addEventListener("click", () => chrome.tabs.create({ url: "http://localhost:8081/" }));
render().catch((error) => { message.textContent = error.message; });
