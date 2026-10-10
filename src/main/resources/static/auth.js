const $ = (selector) => document.querySelector(selector);

async function api(path, options = {}) {
  const response = await fetch(path, {
    cache: "no-store",
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  const payload = await response.json().catch(() => ({ success: false, message: `HTTP ${response.status}` }));
  if (!response.ok || payload.success === false) throw new Error(payload.message || "请求失败");
  return payload.data;
}

function showLoginError(error) {
  const target = $("#loginError");
  target.textContent = error?.message || String(error);
  target.hidden = false;
}

function showRegisterError(error) {
  const target = $("#registerError");
  target.textContent = error?.message || String(error);
  target.hidden = false;
}

let registrationStores = [];
let registrationKey = "";

function resetRegistrationStores(message = "填写 MCP Key 后读取可注册店铺。") {
  registrationStores = [];
  registrationKey = "";
  const select = $("#registerStore");
  select.innerHTML = '<option value="">请先验证 MCP Key</option>';
  select.disabled = true;
  $("#submitRegister").disabled = true;
  $("#registerStoreStatus").textContent = message;
  $("#registerAccountHint").textContent = "选择后自动确定登录账号。";
}

function closeRegistration() {
  $("#registerDialog").close();
  $("#openRegister").focus();
}

async function initialize() {
  try {
    const status = await api("/api/auth/status");
    if (status.setupRequired) {
      location.replace("/setup.html");
      return;
    }
    if (status.authenticated) {
      location.replace("/");
      return;
    }
    $("#loginSuccess").hidden = new URLSearchParams(location.search).get("setup") !== "1";
  } catch (error) {
    showLoginError(error);
  }
}

$("#loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("#loginError").hidden = true;
  const button = event.submitter;
  button.disabled = true;
  button.textContent = "正在登录…";
  try {
    const result = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username: $("#loginStoreName").value.trim(),
        password: $("#loginPassword").value,
      }),
    });
    location.replace(result.redirect || "/");
  } catch (error) {
    showLoginError(error);
    button.disabled = false;
    button.textContent = "登录";
  }
});

$("#openRegister").addEventListener("click", () => {
  $("#registerError").hidden = true;
  $("#registerDialog").showModal();
  requestAnimationFrame(() => $("#registerMcpKey").focus());
});

$("#closeRegister").addEventListener("click", closeRegistration);
$("#cancelRegister").addEventListener("click", closeRegistration);
$("#registerDialog").addEventListener("cancel", (event) => {
  event.preventDefault();
  closeRegistration();
});
$("#registerDialog").addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    event.preventDefault();
    closeRegistration();
  }
});

$("#registerMcpKey").addEventListener("input", () => {
  if (registrationKey && $("#registerMcpKey").value.trim() !== registrationKey) {
    resetRegistrationStores("MCP Key 已改变，请重新验证并读取店铺。");
  }
});

$("#registerAbbreviation").addEventListener("input", (event) => {
  event.target.value = event.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 12);
});

function validatePasswordConfirmation() {
  const confirmation = $("#registerPasswordConfirm");
  confirmation.setCustomValidity(
    confirmation.value && confirmation.value !== $("#registerPassword").value ? "两次输入的密码不一致" : "",
  );
}

$("#registerPassword").addEventListener("input", validatePasswordConfirmation);
$("#registerPasswordConfirm").addEventListener("input", validatePasswordConfirmation);

$("#discoverRegisterStores").addEventListener("click", async (event) => {
  const key = $("#registerMcpKey").value.trim();
  if (!key) {
    showRegisterError(new Error("请先填写领星 MCP Key"));
    $("#registerMcpKey").focus();
    return;
  }
  $("#registerError").hidden = true;
  const button = event.currentTarget;
  const originalLabel = button.textContent;
  button.disabled = true;
  button.textContent = "正在读取…";
  $("#registerStoreStatus").textContent = "正在连接领星并读取可用店铺…";
  try {
    const stores = await api("/api/auth/register/discover", {
      method: "POST",
      body: JSON.stringify({ mcpKey: key }),
    });
    registrationStores = Array.isArray(stores) ? stores : [];
    registrationKey = key;
    const select = $("#registerStore");
    select.innerHTML = '<option value="">请选择要注册的店铺</option>'
      + registrationStores.map((store) => `<option value="${escapeHtml(store.storeId)}">${escapeHtml(store.storeName)}</option>`).join("");
    select.disabled = registrationStores.length === 0;
    $("#registerStoreStatus").textContent = registrationStores.length
      ? `已读取 ${registrationStores.length} 个店铺，请选择要注册的店铺。`
      : "当前 MCP Key 未读取到沃尔玛店铺。";
    $("#submitRegister").disabled = true;
    if (registrationStores.length) select.focus();
  } catch (error) {
    resetRegistrationStores("连接失败，请检查 MCP Key 后重试。");
    showRegisterError(error);
  } finally {
    button.disabled = false;
    button.textContent = originalLabel;
  }
});

$("#registerStore").addEventListener("change", (event) => {
  const selected = registrationStores.find((store) => String(store.storeId) === event.target.value);
  $("#registerAccountHint").textContent = selected
    ? `登录账号将使用店铺名：${selected.storeName}`
    : "选择后自动确定登录账号。";
  $("#submitRegister").disabled = !selected;
});

$("#registerForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("#registerError").hidden = true;
  validatePasswordConfirmation();
  if (!event.currentTarget.reportValidity()) return;
  const selected = registrationStores.find((store) => String(store.storeId) === $("#registerStore").value);
  if (!selected || $("#registerMcpKey").value.trim() !== registrationKey) {
    showRegisterError(new Error("请重新验证 MCP Key 并选择店铺"));
    return;
  }
  const button = $("#submitRegister");
  button.disabled = true;
  button.textContent = "正在创建并登录…";
  try {
    const result = await api("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({
        mcpKey: registrationKey,
        storeId: selected.storeId,
        storeAbbreviation: $("#registerAbbreviation").value.trim(),
        password: $("#registerPassword").value,
        confirmPassword: $("#registerPasswordConfirm").value,
      }),
    });
    location.replace(result.redirect || "/");
  } catch (error) {
    showRegisterError(error);
    button.disabled = false;
    button.textContent = "注册并进入工作台";
  }
});

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

initialize();
