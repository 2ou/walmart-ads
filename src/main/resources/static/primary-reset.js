const $=(selector)=>document.querySelector(selector);
async function api(path,options={}){const response=await fetch(path,{cache:"no-store",headers:{"Content-Type":"application/json"},...options});const payload=await response.json().catch(()=>({success:false,message:`HTTP ${response.status}`}));if(!response.ok||payload.success===false)throw new Error(payload.message||"请求失败");return payload.data;}
api("/api/auth/status").then((status)=>{if(status.setupRequired)location.replace("/setup.html");}).catch((error)=>{$("#resetError").textContent=error.message;$("#resetError").hidden=false;});
$("#resetForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const error = $("#resetError");
  const success = $("#resetSuccess");
  error.hidden = true;
  success.hidden = true;
  const password = $("#newPassword").value;
  const confirmation = $("#confirmPassword").value;
  if (password !== confirmation) {
    error.textContent = "两次输入的密码不一致";
    error.hidden = false;
    $("#confirmPassword").focus();
    return;
  }
  const button = event.submitter;
  button.disabled = true;
  try {
    await api(form.action, {
      method: "POST",
      body: JSON.stringify({newPassword: password, confirmPassword: confirmation}),
    });
    success.textContent = "主工作区密码已重置，可以返回登录。";
    success.hidden = false;
    form.reset();
  } catch (reason) {
    error.textContent = reason.message;
    error.hidden = false;
  } finally {
    button.disabled = false;
  }
});
