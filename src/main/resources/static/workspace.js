const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
let account = null;
let stores = [];
let newStores = [];

async function api(path, options = {}) {
  const response = await fetch(path, {
    cache: "no-store",
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  if (response.status === 401) {
    location.replace("/login.html");
    throw new Error("请先登录");
  }
  const payload = await response.json().catch(() => ({ success: false, message: `HTTP ${response.status}` }));
  if (!response.ok || payload.success === false) throw new Error(payload.message || "请求失败");
  return payload.data;
}
function escapeHtml(value) { return String(value ?? "").replace(/[&<>'"]/g, (char) => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", "'":"&#39;", '"':"&quot;" }[char])); }
function split(value) { return String(value || "").split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean); }
function toast(message) { const el=$("#toast"); el.textContent=message; el.hidden=false; clearTimeout(toast.timer); toast.timer=setTimeout(()=>{el.hidden=true;},2600); }
function fail(error) { const el=$("#pageError"); el.textContent=error?.message||String(error); el.hidden=false; window.scrollTo({top:0,behavior:window.matchMedia("(prefers-reduced-motion: reduce)").matches?"auto":"smooth"}); }
function dateTime(value) { return value ? new Intl.DateTimeFormat("zh-CN",{dateStyle:"medium",timeStyle:"short"}).format(new Date(value)) : "—"; }
function renderStoreOptions(target, rows, selected) { target.innerHTML='<option value="">选择 Walmart 店铺</option>'+rows.map((store)=>`<option value="${escapeHtml(store.storeId)}" ${store.storeId===selected?"selected":""}>${escapeHtml(store.storeName)} · ${escapeHtml(store.storeId)}</option>`).join(""); }

async function loadAccount() {
  account=await api("/api/workspace/me");
  const display=account.storeAbbreviation||account.storeName||"待绑定店铺";
  $("#sideStore").textContent=display; $("#sideUser").textContent=account.username;
  $("#heroStore").textContent=display; $("#heroMeta").textContent=account.storeId?`${account.storeName} · ${account.storeId}`:"尚未确认 Store ID";
  $("#heroStatus").textContent=account.status==="ACTIVE"?"已绑定":"待配置"; $("#heroInit").textContent=account.initializationStatus||"—";
  $("#pendingNotice").hidden=account.status==="ACTIVE"; $("#backWorkbench").hidden=account.status!=="ACTIVE";
  $("#keyState").textContent=account.mcpConfigured?"已配置":"未配置"; $("#keyState").classList.toggle("ok",account.mcpConfigured);
  $("#keyHint").textContent=account.mcpConfigured?`已保存 Key，尾号 ${account.mcpKeyLast4}`:"数据库中未配置";
  $("#mcpBaseUrl").value=account.mcpBaseUrl||"https://openmcp.lingxing.com/mcp-servers/lingxing-mcp";
  $("#storeAbbreviation").value=account.storeAbbreviation||"";
  $("#advertiserIds").value=(account.advertiserIds||[]).join(", ");
  $("#skuPrefixes").value=(account.skuPrefixes||[]).join(", ");
  $("#accountUsername").textContent=account.username;
  $("#accountAbbreviation").textContent=account.storeAbbreviation||"—";
  $("#accountStoreId").textContent=account.storeId||"—";
  $("#accountSupplyChain").textContent=account.supplyChainEnabled?"已启用（PINKSIR）":"未启用";
  if(account.storeId){stores=[{storeId:account.storeId,storeName:account.storeName||account.storeId}];renderStoreOptions($("#storeSelect"),stores,account.storeId);}
  const isPinksir=String(account.username||"").toUpperCase()==="PINKSIR";
  $("#managedAccountsCard").hidden=!isPinksir;
  if(isPinksir) await loadManagedAccounts();
}

async function discoverStores(){
  const button=$("#testMcp");button.disabled=true;button.textContent="正在验证…";$("#testMcpResult").textContent="正在通过领星读取店铺";
  try{stores=await api("/api/workspace/discover",{method:"POST",body:JSON.stringify({mcpKey:$("#mcpKey").value||null,baseUrl:$("#mcpBaseUrl").value})});renderStoreOptions($("#storeSelect"),stores,account?.storeId);$("#testMcpResult").textContent=`连接成功，发现 ${stores.length} 个可访问店铺`;toast("领星连接验证成功");}
  catch(error){$("#testMcpResult").textContent=error.message;fail(error);}finally{button.disabled=false;button.textContent="测试连接并读取店铺";}
}
async function discoverNewStores(){
  const button=$("#discoverNewStore");button.disabled=true;button.textContent="正在读取…";$("#newStoreStatus").textContent="正在验证新店铺连接";
  try{newStores=await api("/api/workspace/discover",{method:"POST",body:JSON.stringify({mcpKey:$("#newMcpKey").value,baseUrl:$("#newMcpBaseUrl").value})});renderStoreOptions($("#newStoreSelect"),newStores,null);$("#newStoreSelect").disabled=false;$("#newStoreStatus").textContent=`发现 ${newStores.length} 个 Walmart 店铺`;}
  catch(error){fail(error);}finally{button.disabled=false;button.textContent="测试连接并读取店铺";}
}

async function loadSessions(){const rows=await api("/api/workspace/sessions");$("#sessionList").innerHTML=rows.map((row)=>`<div class="session-row"><div><strong>${row.current?"当前浏览器":"其他浏览器"}</strong><small>${escapeHtml(row.ipAddress||"未知地址")} · ${escapeHtml(row.userAgent||"未知设备")}</small><small>最近活动 ${dateTime(row.lastSeenAt)}</small></div>${row.current?"":`<button data-revoke="${row.id}">撤销</button>`}</div>`).join("")||"没有有效会话";}
async function loadAudit(){const rows=await api("/api/workspace/audit");$("#auditList").innerHTML=rows.map((row)=>`<div class="audit-row"><span>${escapeHtml(row.eventType)}</span><div><strong>${escapeHtml(row.summary||"—")}</strong><small>${escapeHtml(row.ipAddress||"—")} · ${row.success?"成功":"失败"}</small></div><small>${dateTime(row.createdAt)}</small></div>`).join("")||"暂无审计记录";}
async function loadManagedAccounts(){
  const rows=await api("/api/workspace/managed-accounts");
  $("#managedAccountList").innerHTML=rows.map((row)=>`<div class="session-row"><div><strong>${escapeHtml(row.username)} ${row.storeAbbreviation?`· ${escapeHtml(row.storeAbbreviation)}`:""}</strong><small>${escapeHtml(row.storeId||"未绑定 Store ID")} · ${row.mcpConfigured?"MCP 已配置":"MCP 未配置"} · ${escapeHtml(row.initializationStatus||row.status)}</small></div>${String(row.username).toUpperCase()==="PINKSIR"?"":`<button data-managed-reset="${row.id}" data-managed-name="${escapeHtml(row.username)}">重置密码</button>`}</div>`).join("")||"暂无店铺账号";
}

function setCreateAccountOpen(open, restoreFocus=false){
  const card=$("#createAccountCard");
  const trigger=$("#toggleCreateAccount");
  card.hidden=!open;
  trigger.setAttribute("aria-expanded",String(open));
  if(open){requestAnimationFrame(()=>{$("#newMcpKey").focus();card.scrollIntoView({block:"start",behavior:matchMedia("(prefers-reduced-motion: reduce)").matches?"auto":"smooth"});});}
  else if(restoreFocus)trigger.focus();
}

$$('[data-tab]').forEach((button)=>button.addEventListener("click",()=>{const tab=button.dataset.tab;$$('[data-tab]').forEach((item)=>item.classList.toggle("is-active",item===button));$$('[data-panel]').forEach((panel)=>panel.classList.toggle("is-active",panel.dataset.panel===tab));if(tab==="security")loadSessions().catch(fail);if(tab==="audit")loadAudit().catch(fail);}));
$("#testMcp").addEventListener("click",discoverStores);
$("#toggleCreateAccount").addEventListener("click",()=>setCreateAccountOpen($("#createAccountCard").hidden));
$("#closeCreateAccount").addEventListener("click",()=>setCreateAccountOpen(false,true));
$("#discoverNewStore").addEventListener("click",discoverNewStores);
$("#bindingForm").addEventListener("submit",async(event)=>{event.preventDefault();const selected=stores.find((store)=>store.storeId===$("#storeSelect").value);if(!selected){fail(new Error("请先测试连接并选择当前店铺"));return;}const button=event.submitter;button.disabled=true;try{await api("/api/workspace/binding",{method:"POST",body:JSON.stringify({mcpKey:$("#mcpKey").value||null,baseUrl:$("#mcpBaseUrl").value,storeId:selected.storeId,skuPrefixes:split($("#skuPrefixes").value),advertiserIds:split($("#advertiserIds").value).map(Number).filter(Number.isFinite)})});$("#mcpKey").value="";await loadAccount();toast("领星配置已保存，初始化同步已进入队列");}catch(error){fail(error);}finally{button.disabled=false;}});
$("#revealKey").addEventListener("click",async()=>{try{const result=await api("/api/workspace/mcp-key?purpose=view");$("#mcpKey").value=result.key;$("#mcpKey").type="text";toast("已显示当前工作区 Key");}catch(error){fail(error);}});
$("#copyKey").addEventListener("click",async()=>{try{const result=await api("/api/workspace/mcp-key?purpose=copy");await navigator.clipboard.writeText(result.key);toast("MCP Key 已复制");}catch(error){fail(error);}});
$("#createAccountForm").addEventListener("submit",async(event)=>{event.preventDefault();if($("#newPassword").value!==$("#newPasswordConfirm").value){fail(new Error("两次输入的密码不一致"));return;}const selected=newStores.find((store)=>store.storeId===$("#newStoreSelect").value);if(!selected){fail(new Error("请先测试连接并选择新店铺"));return;}const button=event.submitter;button.disabled=true;try{await api("/api/workspace/accounts",{method:"POST",body:JSON.stringify({password:$("#newPassword").value,confirmPassword:$("#newPasswordConfirm").value,mcpKey:$("#newMcpKey").value,baseUrl:$("#newMcpBaseUrl").value,storeId:selected.storeId,storeAbbreviation:$("#newStoreAbbreviation").value,advertiserIds:split($("#newAdvertiserIds").value).map(Number).filter(Number.isFinite)})});event.target.reset();$("#newMcpBaseUrl").value="https://openmcp.lingxing.com/mcp-servers/lingxing-mcp";$("#newStoreSelect").disabled=true;newStores=[];setCreateAccountOpen(false,true);toast(`店铺 ${selected.storeName} 已创建，可直接登录`);if(String(account.username).toUpperCase()==="PINKSIR")await loadManagedAccounts();}catch(error){fail(error);}finally{button.disabled=false;}});
$("#passwordForm").addEventListener("submit",async(event)=>{event.preventDefault();if($("#newPasswordOwn").value!==$("#confirmPasswordOwn").value){fail(new Error("两次输入的新密码不一致"));return;}const button=event.submitter;button.disabled=true;try{await api("/api/workspace/password",{method:"POST",body:JSON.stringify({newPassword:$("#newPasswordOwn").value,confirmPassword:$("#confirmPasswordOwn").value})});event.target.reset();toast("密码已修改，现有会话继续有效");}catch(error){fail(error);}finally{button.disabled=false;}});
$("#sessionList").addEventListener("click",async(event)=>{const button=event.target.closest("[data-revoke]");if(!button)return;await api(`/api/workspace/sessions/${button.dataset.revoke}/revoke`,{method:"POST"});await loadSessions();});
$("#managedAccountList").addEventListener("click",(event)=>{const button=event.target.closest("[data-managed-reset]");if(!button)return;$("#managedWorkspaceId").value=button.dataset.managedReset;$("#managedPasswordTarget").textContent=`为 ${button.dataset.managedName} 设置新密码；其现有会话不会退出。`;$("#managedPasswordDialog").showModal();});
$("#managedPasswordForm").addEventListener("submit",async(event)=>{event.preventDefault();if($("#managedNewPassword").value!==$("#managedConfirmPassword").value){fail(new Error("两次输入的新密码不一致"));return;}const button=event.submitter;button.disabled=true;try{await api(`/api/workspace/managed-accounts/${$("#managedWorkspaceId").value}/password`,{method:"POST",body:JSON.stringify({newPassword:$("#managedNewPassword").value,confirmPassword:$("#managedConfirmPassword").value})});event.target.reset();$("#managedPasswordDialog").close();toast("店铺密码已重置");await loadManagedAccounts();}catch(error){fail(error);}finally{button.disabled=false;}});
$("#cancelManagedPassword").addEventListener("click",()=>$("#managedPasswordDialog").close());
$("#refreshManagedAccounts").addEventListener("click",()=>loadManagedAccounts().catch(fail));
$("#refreshSessions").addEventListener("click",()=>loadSessions().catch(fail));$("#refreshAudit").addEventListener("click",()=>loadAudit().catch(fail));
$("#logoutButton").addEventListener("click",async()=>{await api("/api/auth/logout",{method:"POST"});location.replace("/login.html");});

loadAccount().catch(fail);
