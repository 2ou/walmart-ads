const PAGE_SOURCE = "WALMART_WORKBENCH";
const EXTENSION_SOURCE = "WALMART_WORKBENCH_EXTENSION";

function announce(type, extra = {}) {
  window.postMessage({ source: EXTENSION_SOURCE, type, ...extra }, window.location.origin);
}

window.addEventListener("message", (event) => {
  if (event.source !== window || event.origin !== window.location.origin) return;
  const message = event.data;
  if (message?.source !== PAGE_SOURCE || message.type !== "CURRENT_CHROME_BRIDGE") return;
  chrome.runtime.sendMessage({
    type: "WORKBENCH_BRIDGE_SESSION",
    baseUrl: window.location.origin,
    bootstrap: message.bootstrap,
    command: message.command || null
  }).then((result) => {
    if (result?.ok) announce("CURRENT_CHROME_BRIDGE_ACK", { data: result.data || null });
    else announce("CURRENT_CHROME_BRIDGE_ERROR", { message: result?.error || "扩展未响应" });
  }).catch((error) => announce("CURRENT_CHROME_BRIDGE_ERROR", { message: error.message }));
});

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type === "REQUEST_BRIDGE_BOOTSTRAP") announce("CURRENT_CHROME_BRIDGE_READY");
});

announce("CURRENT_CHROME_BRIDGE_READY");
