const $ = (selector) => document.querySelector(selector);
let stores = [];

async function api(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", headers: { "Content-Type": "application/json", ...(options.headers || {}) }, ...options });
  const payload = await response.json().catch(() => ({ success: false, message: `HTTP ${response.status}` }));
  if (!response.ok || payload.success === false) throw new Error(payload.message || "请求失败");
  return payload.data;
}
function values(value) { return String(value || "").split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean); }
function showError(error) { const target=$("#setupError"); target.textContent=error?.message||String(error); target.hidden=false; }
function escapeHtml(value) { return String(value ?? "").replace(/[&<>'"]/g, (char) => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"}[char])); }

async function initialize() {
  try {
    const status = await api("/api/auth/status");
    if (!status.setupRequired) { location.replace("/login.html"); return; }
    const legacy = await api("/api/auth/bootstrap");
    $("#setupBaseUrl").value = legacy.baseUrl || $("#setupBaseUrl").value;
    $("#setupAdvertisers").value = (legacy.advertiserIds || []).join(", ");
    $("#legacyKeyHint").textContent = legacy.configured
      ? `已检测到现有领星配置（Key 尾号 ${legacy.keyLast4}），留空即可使用。`
      : "未检测到现有 Key，请填写后测试连接。";
  } catch (error) { showError(error); }
}

$("#toggleSetupKey").addEventListener("click", () => {
  const input=$("#setupMcpKey"); input.type=input.type==="password"?"text":"password";
  $("#toggleSetupKey").textContent=input.type==="password"?"显示":"隐藏";
});
$("#discoverStores").addEventListener("click", async () => {
  const button=$("#discoverStores"); button.disabled=true; button.textContent="正在读取…"; $("#setupError").hidden=true;
  try {
    const discovered=await api("/api/auth/discover",{method:"POST",body:JSON.stringify({mcpKey:$("#setupMcpKey").value||null,baseUrl:$("#setupBaseUrl").value})});
    stores=discovered.filter((store)=>String(store.storeName||"").trim().toUpperCase()==="PINKSIR");
    if(!stores.length) throw new Error("当前 MCP Key 未发现 PINKSIR 店铺，请检查 Key 的店铺权限");
    $("#setupStore").innerHTML=stores.map((store)=>`<option value="${escapeHtml(store.storeId)}">${escapeHtml(store.storeName)} · ${escapeHtml(store.storeId)}</option>`).join("");
    $("#setupStore").disabled=false; $("#finishSetup").disabled=false; $("#discoverStatus").textContent="已确认 PINKSIR 店铺";
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
