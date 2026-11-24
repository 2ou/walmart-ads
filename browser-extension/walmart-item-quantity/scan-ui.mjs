export function initScanUi(chromeApi, doc, clipboard, confirmReplace) {
  const el = id => doc.getElementById(id);
  let state, currentTab, currentVariants;
  function show() {
    const active = state && ['waiting', 'navigating'].includes(state.phase);
    const paused = state?.phase === 'paused';
    el('scan-start').disabled = !!active || !currentTab || !currentVariants?.length;
    el('scan-pause').disabled = !active;
    el('scan-resume').disabled = !paused;
    el('scan-stop').disabled = !active && !paused;
    el('scan-copy').disabled = !state?.results?.length;
    const labels = { waiting: '等待中', navigating: '读取中', paused: '已暂停', stopped: '已停止', complete: '已完成' };
    el('scan-status').textContent = state ? `${labels[state.phase] || '未知状态'}：${state.results?.length || 0}/${state.queue?.length || 0}。${state.reason || (state.phase === 'waiting' ? '等待下一次查询。' : '')}` : '未开始。';
  }
  async function message(type, extra = {}) {
    try {
      const result = await chromeApi.runtime.sendMessage({ type, ...extra });
      if (!result?.ok) throw new Error(result?.error || '操作失败。');
      state = result.state; show();
    } catch (error) { el('scan-status').textContent = `操作失败：${error.message}`; }
  }
  el('scan-start').addEventListener('click', () => {
    const replace = !!state?.results?.length;
    if (replace && !confirmReplace('开始新任务会覆盖之前的本地结果。请先复制保存；确定覆盖吗？')) return;
    message('scan-start', { tabId: currentTab, variants: currentVariants, replace });
  });
  for (const action of ['pause', 'resume', 'stop']) el(`scan-${action}`).addEventListener('click', () => message(`scan-${action}`));
  el('scan-copy').addEventListener('click', async () => {
    const columns = ['itemId','productId','color','size','status','topOrderLimit','shippingOrderLimit','maxOrderQuantity','availableQuantity','capturedAt'];
    const safe = value => { const v = String(value ?? '').replace(/[\t\r\n]+/g, ' ').trim(); return /^[=+\-@]/.test(v) ? "'" + v : v; };
    const output = [columns, ...state.results.map(row => columns.map(col => row[col]))].map(row => row.map(safe).join('\t')).join('\n');
    try { await clipboard.writeText(output); el('scan-status').textContent = `已复制 ${state.results.length} 条结果。`; }
    catch { el('scan-status').textContent = '复制失败，请检查剪贴板权限。'; }
  });
  chromeApi.storage.onChanged.addListener((changes, area) => { if (area === 'local' && changes.variantScan) { state = changes.variantScan.newValue; show(); } });
  message('scan-state');
  return { setPage(tabId, variants) { currentTab = tabId; currentVariants = variants; show(); } };
}
