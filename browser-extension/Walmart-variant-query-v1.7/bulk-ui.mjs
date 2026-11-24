import { readBulkEntry, analyzeBulk, bulkCsv, bulkTsv, BULK_KEY } from './bulk.mjs';

const ENTRY_ERRORS = {
  'unsupported-page': '请先打开 Walmart 美国站的商品详情页。',
  'no-page-data': '商品数据尚不可读。请等页面加载完再试。',
  'no-variant-map': '商品页未返回变体清单。请刷新后再试。',
};

const FAILURE_LABELS = {
  blocked: '访问受限',
  'not-found': '商品页不存在',
  'empty-page': '商品页内容为空',
  'no-product-data': '商品页未返回数据',
  timeout: '请求超时',
  'fetch-failed': '请求失败',
  'bad-entry': '变体信息不完整',
  unknown: '未知原因',
};

const DIMENSION_LABELS = new Map([
  ['actual_color', '颜色'], ['color', '颜色'], ['pattern', '图案'], ['variant', '变体'],
]);

const hasLimit = row => typeof row?.orderLimit === 'number' && Number.isFinite(row.orderLimit);
const isOutOfStock = row => Boolean(row.availabilityStatus && row.availabilityStatus !== 'IN_STOCK');

export function initBulkUi(chromeApi, doc, clipboard, confirmStart) {
  const el = id => doc.getElementById(id);
  const nf = new Intl.NumberFormat('zh-CN');
  let state = null;
  let page = null;
  let initializing = true;
  let starting = false;
  let pending = false;
  let storageRevision = 0;
  let showOperationError = false;

  const esc = value => String(value ?? '').replace(/[&<>"]/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch]));
  const num = value => typeof value === 'number' && Number.isFinite(value) ? nf.format(value) : '—';
  const phaseText = { idle: '未开始', running: '读取中', paused: '已暂停', complete: '已完成', stopped: '已停止', partial: '部分完成', error: '查询失败' };

  function note(text, error = false) {
    const output = el('bulk-copy-status');
    output.textContent = text;
    output.dataset.state = error ? 'error' : 'success';
  }

  function renderProgress(done, total) {
    const wrap = el('bulk-progress');
    const live = state && (state.phase === 'running' || state.phase === 'paused');
    if (!live || !total) { wrap.hidden = true; return; }
    const pct = Math.max(0, Math.min(100, (done / total) * 100));
    el('bulk-progress-bar').style.width = `${pct.toFixed(1)}%`;
    wrap.dataset.state = state.phase;
    wrap.setAttribute('role', 'progressbar');
    wrap.setAttribute('aria-label', '整款查询进度');
    wrap.setAttribute('aria-valuemin', '0');
    wrap.setAttribute('aria-valuemax', '100');
    wrap.setAttribute('aria-valuenow', String(Math.round(pct)));
    wrap.setAttribute('aria-valuetext', `已处理 ${nf.format(done)} / ${nf.format(total)} 个变体`);
    wrap.hidden = false;
  }

  function renderMatrixCell(row) {
    if (!row) return '<td class="num empty" title="该组合暂无结果"><span aria-hidden="true">·</span><span class="sr-only">暂无结果</span></td>';

    const limit = hasLimit(row) ? row.orderLimit : null;
    if (isOutOfStock(row)) {
      const title = limit === null ? '缺货，页面未返回下单上限' : `缺货，页面显示上限 ${nf.format(limit)} 件`;
      const quantity = limit === null ? '' : `<span class="cell-quantity">${nf.format(limit)}<span class="sr-only"> 件</span></span> `;
      return `<td class="num oos" title="${esc(title)}">${quantity}<span class="cell-marker">缺货</span></td>`;
    }
    if (limit === null) return '<td class="num missing" title="页面未返回下单上限"><span aria-hidden="true">—</span><span class="sr-only">未返回上限</span></td>';
    if (limit === 0) return '<td class="num zero" title="下单上限为 0 件">0<span class="sr-only"> 件</span></td>';
    if (limit < 10) return `<td class="num low" title="告急，下单上限 ${nf.format(limit)} 件">${nf.format(limit)}<span class="sr-only"> 件，告急</span></td>`;
    return `<td class="num">${nf.format(limit)}<span class="sr-only"> 件</span></td>`;
  }

  function renderMatrix(analysis) {
    const wrap = el('bulk-matrix-wrap');
    const section = el('bulk-matrix-section');
    const visible = analysis.rows.length > 0;
    wrap.hidden = !visible;
    if (section) section.hidden = !visible;
    if (!visible) return;

    const sized = analysis.rows.some(row => row.sizeKey);
    const dimension = DIMENSION_LABELS.get(analysis.dim) ?? analysis.dim;
    const sizeLabels = new Map(analysis.rows.map(row => [row.size, row.sizeLabel]));
    const head = ['<thead><tr><th scope="col">' + esc(dimension) + '</th>']
      .concat(analysis.sizes.map(size => `<th scope="col">${esc(sized ? sizeLabels.get(size) || size : '下单上限')}</th>`))
      .concat(['<th scope="col" class="total">合计</th></tr></thead>']);
    const knownRows = analysis.rows.filter(hasLimit);
    const knownValues = new Set(knownRows.map(row => row.value));
    const knownSizes = new Set(knownRows.map(row => row.size));
    const totalCell = (total, known) => known ? nf.format(total) : '<span aria-hidden="true">—</span><span class="sr-only">未返回可汇总的上限</span>';
    const body = analysis.values.map(value => {
      const cells = analysis.sizes.map(size => renderMatrixCell(analysis.cells[value.value]?.[size])).join('');
      return `<tr><th scope="row" title="${esc(value.label)}">${esc(value.label)}</th>${cells}<td class="num total">${totalCell(value.total, knownValues.has(value.value))}</td></tr>`;
    }).join('');
    const foot = `<tfoot><tr><th scope="row">合计</th>${analysis.bySize.map(size => `<td class="num">${totalCell(size.total, knownSizes.has(size.size))}</td>`).join('')}<td class="num total">${totalCell(analysis.total.sum, knownRows.length > 0)}</td></tr></tfoot>`;
    el('bulk-matrix').innerHTML = '<caption class="sr-only">各变体的页面下单上限，单位为件</caption>' + head.join('') + `<tbody>${body}</tbody>` + foot;
  }

  function renderSource() {
    const source = el('bulk-source');
    if (!source) return;
    const { itemId, productId } = page || {};
    const hasIdentity = Boolean(itemId || productId);
    const ownIdMatches = (itemId && state?.itemId === itemId) || (productId && state?.productId === productId);
    const rowsMatch = state?.rows?.some(row => (itemId && row.itemId === itemId) || (productId && row.productId === productId));
    const queueMatches = state?.queue?.some(row => (itemId && row.knownItemId === itemId) || (productId && row.productId === productId));
    const canCompare = ownIdMatches || (itemId && state?.itemId) || (productId && state?.productId);
    const previous = state && (!page || (hasIdentity && canCompare && !ownIdMatches && !rowsMatch && !queueMatches));
    const text = !previous ? '' : page
      ? `正在显示另一商品的任务（入口 Item ${state.itemId || '未知'}）。`
      : `正在显示已保存的任务（入口 Item ${state.itemId || '未知'}）。打开 Walmart 商品页可发起新查询。`;
    if (source.textContent !== text) source.textContent = text;
    source.hidden = !previous;
  }

  function render() {
    const running = state?.phase === 'running';
    const paused = state?.phase === 'paused';
    const hasRows = !!(state?.rows?.length);
    const failed = state?.failures?.length || 0;
    const canRetry = failed > 0 && ['complete', 'stopped', 'paused'].includes(state.phase);
    const completion = state?.phase === 'complete' && failed ? (hasRows ? 'partial' : 'error') : state?.phase || 'idle';
    const controlsLocked = initializing || starting || pending;
    const panel = el('bulk-panel');
    if (panel) {
      panel.dataset.resultState = starting ? 'preparing' : completion;
      panel.dataset.pageState = page ? 'supported' : 'unsupported';
    }

    el('bulk-start').disabled = controlsLocked || !page || running || paused;
    el('bulk-pause').disabled = controlsLocked || !running;
    el('bulk-resume').disabled = controlsLocked || !paused;
    el('bulk-stop').disabled = controlsLocked || (!running && !paused);
    el('bulk-csv').disabled = !hasRows;
    el('bulk-copy').disabled = !hasRows;
    el('bulk-retry').disabled = controlsLocked || !canRetry;
    el('bulk-retry').hidden = !canRetry;

    el('bulk-live').hidden = !running && !paused;
    el('bulk-pause').hidden = !running;
    el('bulk-resume').hidden = !paused;
    el('bulk-start').hidden = running || paused;
    el('bulk-start').textContent = starting ? '正在准备…' : state ? '重新查询' : '开始查询';
    const actions = el('bulk-actions');
    actions.hidden = !hasRows && !(canRetry && actions.contains(el('bulk-retry')));
    el('bulk-csv').hidden = !hasRows;
    el('bulk-copy').hidden = !hasRows;
    const toolbar = el('bulk-export');
    if (toolbar) toolbar.hidden = !hasRows && !canRetry;

    const phase = el('bulk-phase');
    const status = el('bulk-status');
    phase.textContent = starting ? '准备中' : phaseText[completion] || '未知';
    phase.dataset.state = starting ? 'running' : completion;
    if (!showOperationError) status.dataset.state = starting ? 'running' : completion;
    renderSource();

    if (!state) {
      if (!showOperationError) status.textContent = starting ? '正在读取商品页的变体清单…' : page ? '读取同款变体的下单上限、在售状态和价格。' : '';
      el('bulk-meta').hidden = true;
      el('bulk-kpi').hidden = true;
      renderMatrix({ rows: [] });
      el('bulk-alert').hidden = true;
      el('bulk-progress').hidden = true;
      el('bulk-price').textContent = '';
      el('bulk-price').hidden = true;
      el('bulk-failures-wrap').hidden = true;
      return;
    }

    const done = state.index || 0;
    const total = state.queue?.length || 0;
    const analysis = analyzeBulk(state);
    const progress = `已处理 ${nf.format(done)} / ${nf.format(total)} 个变体`;
    const completed = `已读取 ${nf.format(analysis.total.sku)} / ${nf.format(analysis.expected)} 个变体`;
    const tally = `${running || paused ? progress : completed}${failed ? ` · ${nf.format(failed)} 个失败` : ''}`;
    const reason = state.reason && !(state.phase === 'complete' || state.reason === '用户已暂停。' || state.reason === '用户已停止。') ? state.reason : '';
    const statusText = starting ? '正在读取商品页的变体清单…' : `${tally}${reason ? `。${reason}` : ''}`;
    if (!showOperationError && status.textContent !== statusText) status.textContent = statusText;
    renderProgress(done, total);

    const meta = [];
    if (state.name) meta.push(`<p class="meta-name">${esc(state.name)}</p>`);
    const facts = [];
    if (state.brand) facts.push(`品牌 <b>${esc(state.brand)}</b>`);
    if (state.seller) facts.push(`卖家 <b>${esc(state.seller)}</b>`);
    if (typeof state.rating === 'number') facts.push(`评分 <b>${state.rating}</b>${typeof state.reviews === 'number' ? `（${nf.format(state.reviews)}）` : ''}`);
    facts.push(`入口 Item <b class="mono">${esc(state.itemId || '—')}</b>`);
    const readAt = state.fetchedAt && new Date(state.fetchedAt);
    if (readAt && Number.isFinite(readAt.getTime())) facts.push(`更新于 ${esc(readAt.toLocaleString('zh-CN'))}`);
    meta.push(`<p class="meta-facts">${facts.map(fact => `<span>${fact}</span>`).join('')}</p>`);
    el('bulk-meta').innerHTML = meta.join('');
    el('bulk-meta').hidden = false;

    const knownLimits = analysis.rows.filter(hasLimit);
    el('kpi-total').textContent = knownLimits.length ? num(analysis.total.sum) : '—';
    el('kpi-sku').textContent = `${nf.format(analysis.total.sku)} / ${nf.format(analysis.expected)}`;
    const stock = el('kpi-stock');
    stock.textContent = `${nf.format(analysis.status.inStock)} / ${nf.format(analysis.status.outOfStock)}`;
    stock.title = `${nf.format(analysis.status.inStock)} 个在售，${nf.format(analysis.status.outOfStock)} 个缺货，${nf.format(analysis.status.unknown)} 个状态未知`;
    el('kpi-avg').textContent = knownLimits.length ? analysis.total.avg.toFixed(1) : '—';
    el('bulk-kpi').hidden = analysis.total.sku === 0;

    const alerts = [];
    const low = analysis.low.filter(row => !isOutOfStock(row));
    const zero = analysis.zero.filter(row => hasLimit(row) && row.orderLimit === 0);
    const rowName = row => row.sizeKey ? `${row.label} ${row.sizeLabel}` : row.label;
    if (low.length) {
      const head = low.slice(0, 6).map(row => `${rowName(row)} ${nf.format(row.orderLimit)} 件`).join('、');
      alerts.push(`<div class="alert-row"><span class="alert-tag">告急 ${nf.format(low.length)} 个</span><span>${esc(head)}${low.length > 6 ? '…' : ''}</span></div>`);
    }
    if (zero.length) alerts.push(`<div class="alert-row"><span class="alert-tag">在售上限为 0 · ${nf.format(zero.length)} 个</span><span>${esc(zero.slice(0, 6).map(rowName).join('、'))}${zero.length > 6 ? '…' : ''}</span></div>`);
    const missingLimit = analysis.rows.length - knownLimits.length;
    if (missingLimit) alerts.push(`<div class="alert-row"><span class="alert-tag">未返回上限 · ${nf.format(missingLimit)} 个</span><span>合计仅包含页面返回的数值。</span></div>`);
    if (analysis.status.unknown) alerts.push(`<div class="alert-row"><span class="alert-tag">在售状态未知 · ${nf.format(analysis.status.unknown)} 个</span><span>页面未返回这些变体的在售状态。</span></div>`);
    el('bulk-alert').innerHTML = alerts.join('');
    el('bulk-alert').hidden = !alerts.length;

    const priceRows = [];
    if (analysis.prices.length === 1) {
      const tier = analysis.prices[0];
      priceRows.push(`<div class="price-line"><span class="price-label">统一价</span><span class="chip-sm">$${esc(tier.price)} × ${tier.count}</span></div>`);
    } else if (analysis.prices.length > 1) {
      priceRows.push(`<div class="price-line"><span class="price-label">价格 ${analysis.prices.length} 档</span>${analysis.prices.map(tier => `<span class="chip-sm">$${esc(tier.price)} × ${tier.count}</span>`).join('')}</div>`);
    }
    if (analysis.wasPrices.length) {
      priceRows.push(`<div class="price-line"><span class="price-label">划线价</span>${analysis.wasPrices.map(tier => `<span class="chip-sm">$${esc(tier.price)} × ${tier.count}</span>`).join('')}</div>`);
    }
    const priceNotes = [];
    if (analysis.noWasCount && analysis.wasPrices.length) priceNotes.push(`${analysis.noWasCount} 个变体未返回划线价`);
    if (analysis.reducedCount) priceNotes.push(`${analysis.reducedCount}/${analysis.total.sku} 个变体标记降价`);
    if (analysis.maxQty.withField) {
      const q = analysis.maxQty;
      priceNotes.push(`两种限购字段有 ${q.same}/${q.withField} 个数值一致${q.missing ? `，${q.missing} 个变体未返回 maxOrderQuantity` : ''}${q.conflict ? `，${q.conflict} 个数值不一致` : ''}`);
    }
    if (analysis.total.sum) priceNotes.push(`上限最高的 5 个变体占合计 ${(analysis.total.top5Share * 100).toFixed(1)}%`);
    if (priceNotes.length) priceRows.push(`<p class="price-note">${esc(priceNotes.join('。'))}。</p>`);
    el('bulk-price').innerHTML = priceRows.join('');
    el('bulk-price').hidden = !priceRows.length;
    renderMatrix(analysis);

    el('bulk-failures-wrap').hidden = !failed;
    const failureSummary = el('bulk-failures-summary');
    if (failureSummary) failureSummary.textContent = `失败明细（${nf.format(failed)}）`;
    el('bulk-failures').textContent = analysis.failureCount.map(({ reason, count }) => `${FAILURE_LABELS[reason] || reason} × ${nf.format(count)}`).join(' · ');
  }

  async function send(type, extra = {}) {
    const revision = storageRevision;
    const result = await chromeApi.runtime.sendMessage({ type, ...extra });
    if (!result?.ok) throw new Error(result?.error || '操作失败。');
    if (revision === storageRevision) state = result.state || null;
    render();
  }

  function restoreControlFocus(id, hadFocus) {
    if (!hadFocus || (doc.activeElement !== doc.body && doc.activeElement !== el(id))) return;
    const target = el(state?.phase === 'running' ? 'bulk-pause' : state?.phase === 'paused' ? 'bulk-resume' : 'bulk-start');
    if (!target.disabled && !target.closest('[hidden]')) target.focus({ preventScroll: true });
  }

  function showActionError(message) {
    showOperationError = true;
    const status = el('bulk-status');
    status.dataset.state = 'error';
    status.textContent = message;
  }

  async function start() {
    if (!page || starting || pending || initializing) return;
    if (state?.rows?.length && !confirmStart('重新查询会覆盖当前保存的整款结果。确认重新查询？')) return;
    const { tabId } = page;
    const hadFocus = doc.activeElement === el('bulk-start');
    let errorMessage = '';
    starting = true;
    showOperationError = false;
    note('');
    render();
    try {
      const [injection] = await chromeApi.scripting.executeScript({ target: { tabId }, func: readBulkEntry });
      const entry = injection?.result;
      if (!entry || entry.error) throw new Error(ENTRY_ERRORS[entry?.error] || '入口页读取失败。');
      await send('bulk-start', { tabId, entry });
    } catch (error) {
      errorMessage = `启动失败：${error.message}`;
    } finally {
      starting = false;
      render();
      if (errorMessage) showActionError(errorMessage);
      restoreControlFocus('bulk-start', hadFocus);
    }
  }

  async function runAction(action, label) {
    if (initializing || starting || pending) return;
    const id = `bulk-${action}`;
    const hadFocus = doc.activeElement === el(id);
    let errorMessage = '';
    pending = true;
    showOperationError = false;
    render();
    try {
      await send(id);
    } catch (error) {
      errorMessage = `${label}失败：${error.message}`;
    } finally {
      pending = false;
      render();
      if (errorMessage) showActionError(errorMessage);
      restoreControlFocus(id, hadFocus);
    }
  }

  function downloadCsv() {
    if (!state?.rows?.length) return;
    const csv = bulkCsv(state);
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = doc.createElement('a');
    anchor.href = url;
    anchor.download = `item-${state.itemId || 'item'}-order-limit.csv`;
    doc.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    note(`已导出 ${analyzeBulk(state).rows.length} 行 CSV。`);
  }

  el('bulk-start').addEventListener('click', () => { void start(); });
  for (const [action, label] of [['pause', '暂停'], ['resume', '继续'], ['stop', '停止'], ['retry', '重试']]) {
    el(`bulk-${action}`).addEventListener('click', () => { void runAction(action, label); });
  }
  el('bulk-csv').addEventListener('click', downloadCsv);
  el('bulk-copy').addEventListener('click', async () => {
    if (!state?.rows?.length) return;
    try {
      await clipboard.writeText(bulkTsv(state));
      note('已复制整款明细，可粘贴到表格。');
    } catch {
      note('复制失败，可改用「下载 CSV」。', true);
    }
  });

  chromeApi.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes[BULK_KEY]) {
      storageRevision++;
      showOperationError = false;
      state = changes[BULK_KEY].newValue || null;
      render();
    }
  });

  void (async () => {
    let errorMessage = '';
    try { await send('bulk-state'); }
    catch (error) { errorMessage = `读取任务失败：${error.message}`; }
    finally {
      initializing = false;
      render();
      if (errorMessage) showActionError(errorMessage);
    }
  })();

  return {
    setPage(tabId, itemId, productId) {
      page = { tabId, itemId: String(itemId || ''), productId: String(productId || '') };
      render();
    },
  };
}
