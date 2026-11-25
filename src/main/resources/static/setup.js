const $ = (selector) => document.querySelector(selector);
let stores = [];
let legacyBootstrap = null;

async function api(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", headers: { "Content-Type": "application/json", ...(options.headers || {}) }, ...options });
  const payload = await response.json().catch(() => ({ success: false, message: `HTTP ${response.status}` }));
  if (!response.ok || payload.success === false) throw new Error(payload.message || "请求失败");
  return payload.data;
}
function values(value) { return String(value || "").split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean); }
function showError(error) { const target=$("#setupError"); target.textContent=error?.message||String(error); target.hidden=false; }

async function initialize() {
  try {
    const status = await api("/api/auth/status");
    if (!status.setupRequired) { location.replace("/login.html"); return; }
    legacyBootstrap = await api("/api/auth/bootstrap");
    $("#setupBaseUrl").value = legacyBootstrap.baseUrl || $("#setupBaseUrl").value;
    $("#legacyKeyHint").textContent = legacyBootstrap.configured
      ? `已检测到现有领星配置（Key 尾号 ${legacyBootstrap.keyLast4}），留空即可使用。`
      : "未检测到现有 Key，请填写后测试连接。";
  } catch (error) { showError(error); }
}

function updateStoreSelection() {
  const selected = stores.find((store) => store.storeId === $("#setupStore").value);
  $("#finishSetup").disabled = !selected;
  const legacyAdvertisers = selected && selected.storeId === legacyBootstrap?.storeId
    ? legacyBootstrap.advertiserIds : [];
  const advertisers = selected?.advertiserIds?.length ? selected.advertiserIds : legacyAdvertisers;
  $("#setupAdvertisers").value = (advertisers || []).join(", ");
}

function resetStoreDiscovery() {
  stores = [];
  const picker = $("#setupStore");
  picker.replaceChildren(new Option("请先测试连接", ""));
  picker.disabled = true;
  $("#discoverStatus").textContent = "确认连接后才能选择店铺";
  updateStoreSelection();
}

$("#toggleSetupKey").addEventListener("click", () => {
  const input=$("#setupMcpKey"); input.type=input.type==="password"?"text":"password";
  $("#toggleSetupKey").textContent=input.type==="password"?"显示":"隐藏";
});
$("#setupStore").addEventListener("change", updateStoreSelection);
$("#setupMcpKey").addEventListener("input", resetStoreDiscovery);
$("#setupBaseUrl").addEventListener("input", resetStoreDiscovery);
$("#discoverStores").addEventListener("click", async () => {
  const button=$("#discoverStores"); button.disabled=true; button.textContent="正在读取…"; $("#setupError").hidden=true;
  resetStoreDiscovery();
  const mcpKey = $("#setupMcpKey").value || null;
  const baseUrl = $("#setupBaseUrl").value;
  try {
    const discovered = await api("/api/auth/discover", {method:"POST", body:JSON.stringify({mcpKey, baseUrl})});
    if (mcpKey !== ($("#setupMcpKey").value || null) || baseUrl !== $("#setupBaseUrl").value) return;
    if (!discovered.length) throw new Error("当前 MCP Key 未发现可访问的 Walmart 店铺");
    stores = discovered;
    const picker = $("#setupStore");
    picker.replaceChildren(new Option("选择领星店铺", ""), ...stores.map((store) =>
      new Option(`${store.storeName} · ${store.storeId}`, store.storeId)));
    picker.value = stores.length === 1 ? stores[0].storeId : "";
    picker.disabled = false;
    updateStoreSelection();
    $("#discoverStatus").textContent = `已读取 ${stores.length} 个可访问店铺`;
  } catch(error){showError(error);} finally {button.disabled=false;button.textContent="测试连接并读取店铺";}
});
$("#setupForm").addEventListener("submit",async(event)=>{
  event.preventDefault(); $("#setupError").hidden=true;
  if($("#setupPassword").value!==$("#setupPasswordConfirm").value){showError(new Error("两次输入的密码不一致"));return;}
  const selected=stores.find((store)=>store.storeId===$("#setupStore").value);
  if(!selected){showError(new Error("请选择领星店铺"));return;}
  const button=event.submitter;button.disabled=true;button.textContent="正在创建工作区…";
  try{
    await api("/api/auth/setup",{method:"POST",body:JSON.stringify({password:$("#setupPassword").value,confirmPassword:$("#setupPasswordConfirm").value,mcpKey:$("#setupMcpKey").value||null,baseUrl:$("#setupBaseUrl").value,storeId:selected.storeId,storeAbbreviation:$("#setupAbbreviation").value,advertiserIds:values($("#setupAdvertisers").value).map(Number).filter(Number.isFinite)})});
    location.replace("/login.html?setup=1");
  }catch(error){showError(error);button.disabled=false;button.textContent="确认店铺并完成初始化";}
});
initialize();
