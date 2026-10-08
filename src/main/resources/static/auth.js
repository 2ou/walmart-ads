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

function showError(error) {
  const target = $("#loginError");
  target.textContent = error?.message || String(error);
  target.hidden = false;
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
    showError(error);
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
    showError(error);
    button.disabled = false;
    button.textContent = "登录";
  }
});

initialize();
