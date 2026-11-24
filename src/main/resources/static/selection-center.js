const API = "/api/selection-center";
const PAGE_SIZE = 25;
const BOARD_COLUMNS = 11;
const WHOLE_NUMBER = new Intl.NumberFormat("zh-CN");
const DOLLARS = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
const CHINA_TIME = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", dateStyle: "medium", timeStyle: "short",
});

function escape(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

function quantity(value) {
  return value != null && Number.isFinite(Number(value)) ? WHOLE_NUMBER.format(Number(value)) : "—";
}

function money(value) {
  return value != null && Number.isFinite(Number(value)) ? DOLLARS.format(Number(value)) : "—";
}

function priceOf(item) {
  const first = money(item.priceMin ?? item.price);
  const last = money(item.priceMax);
  return last !== "—" && first !== last && first !== "—" ? `${first}–${last}` : first;
}

function dateText(value) {
  if (!value) return "";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? String(value) : CHINA_TIME.format(parsed);
}

function itemUrl(id) {
  return /^\d+$/.test(String(id ?? "")) ? `https://www.walmart.com/ip/${id}` : "";
}

function walmartUrl(value) {
  try {
    const url = new URL(value);
    const validHost = /(?:^|\.)(?:walmart\.com|walmartimages\.com)$/i.test(url.hostname);
    return url.protocol === "https:" && validHost && !url.username && !url.password ? url.href : "";
  } catch {
    return "";
  }
}

function externalLink(url, label) {
  return url ? `<a class="selection-link" href="${escape(url)}" target="_blank" rel="noopener noreferrer">${escape(label)} ↗</a>` : "";
}

async function request(path = "", { date, ...options } = {}) {
  const params = new URLSearchParams();
  if (date) params.set("date", date);
  const reply = await fetch(`${API}${path}${params.size ? `?${params}` : ""}`, { cache: "no-store", ...options });
  const payload = await reply.json().catch(() => null);
  if (!reply.ok || payload?.success === false || !payload) {
    throw new Error(payload?.message || `请求失败（${reply.status}）`);
  }
  return payload.data ?? payload;
}

function replaceOptions(select, all, values) {
  const selected = select.value;
  select.replaceChildren(new Option(all, ""), ...values.map((value) => new Option(value, value)));
  if (values.includes(selected)) select.value = selected;
}

function tableMessage(message, columns) {
  return `<tr><td colspan="${columns}"><div class="empty-state"><strong>${escape(message)}</strong></div></td></tr>`;
}

function rankText(rank, previous, canCompare) {
  if (rank == null || !canCompare) return "";
  if (previous == null || Number(previous) > 100) return '<small class="selection-cell-note new">新上榜</small>';
  const change = Number(previous) - Number(rank);
  if (!change) return '<small class="selection-cell-note">持平</small>';
  const message = change > 0 ? `升 ${change} 位` : `降 ${Math.abs(change)} 位`;
  return `<small class="selection-cell-note ${change > 0 ? "up" : "down"}">${message}</small>`;
}

function stockTag(item) {
  if (Number(item.stockIncreaseSkus) <= 0 || item.stockIncreaseSkus == null) return "—";
  return `<button class="selection-stock-tag" type="button" data-selection-increase="${escape(item.itemId)}">库存上涨 · ${quantity(item.stockIncreaseSkus)} 个 SKU</button>`;
}

function currentStock(item) {
  const known = item.currentStockKnownSkus;
  const expected = item.currentStockExpectedSkus;
  if (item.currentStockTotal != null) return { total: item.currentStockTotal, known, expected, status: "已采齐" };
  if (!(expected > 0) || item.groupVerified === false || known >= expected) return { known, expected, status: "待核对 SKU" };
  if (known > 0) return { known, expected, status: `已采 ${quantity(known)}/${quantity(expected)} 个 SKU` };
  return { known, expected, status: "未采集" };
}

function itemRow(item, byCategory) {
  const id = escape(item.itemId);
  const title = escape(item.title || "标题未返回");
  const rank = byCategory ? item.categoryRank : item.rank;
  const previous = byCategory ? item.previousCategoryRank : item.previousRank;
  const stock = currentStock(item);
  const image = walmartUrl(item.imageUrl);
  const thumbnail = image
    ? `<img class="selection-thumb" width="54" height="54" src="${escape(image)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer">`
    : '<span class="selection-thumb">暂无图片</span>';
  return `<tr>
    <td class="selection-rank"><strong>${quantity(rank)}</strong>${rankText(rank, previous, !byCategory || "previousCategoryRank" in item)}</td>
    <td><button class="selection-image-button" type="button" data-selection-item="${id}" aria-label="查看款式：${title}">${thumbnail}</button></td>
    <td><button class="selection-title-button" type="button" data-selection-item="${id}">${title}</button>
      <small class="selection-cell-note">${id}${item.groupVerified === false ? " · 归属待核对" : ""}</small></td>
    <td>${escape(item.category || "—")}</td><td>${escape(item.brand || "—")}</td>
    <td class="num">${priceOf(item)}</td>
    <td class="num selection-current-stock">${stock.total == null ? `<small class="selection-cell-note">${escape(stock.status)}</small>` : `<strong>${quantity(stock.total)} 件</strong>`}</td>
    <td class="num"><strong>${quantity(item.sales)}</strong></td>
    <td class="num">${quantity(item.sevenDaySales)}</td><td class="selection-row-actions">${stockTag(item)}</td>
    <td class="selection-row-actions">${externalLink(itemUrl(item.itemId), "查看沃尔玛")}<button class="selection-watch" type="button" aria-pressed="${Boolean(item.watched)}" data-selection-watch="${id}">${item.watched ? "已关注" : "关注"}</button></td>
  </tr>`;
}

function trendDays(history, asOf, count) {
  const end = new Date(`${asOf}T00:00:00Z`);
  if (!Number.isFinite(end.getTime())) return history.slice(-count);
  const byDate = new Map(history.map((row) => [row.date, row]));
  return Array.from({ length: count }, (_, index) => {
    const time = end.getTime() - (count - index - 1) * 86400000;
    const date = new Date(time).toISOString().slice(0, 10);
    return byDate.get(date) || { date };
  });
}

function renderTrend(container, history, date, days) {
  const dates = trendDays(history, date, days);
  const max = Math.max(1, ...dates.map(({ sales }) => Number(sales) || 0));
  const salesBars = dates.map((row) => {
    const height = row.sales == null ? 0 : Math.max(0, Math.min(100, Number(row.sales) * 100 / max));
    return `<div class="selection-trend-day" ${row.sales == null ? "data-missing" : ""} title="${escape(row.date)} · 销量 ${quantity(row.sales)}"><span style="--selection-trend-height:${height || 0}%"></span></div>`;
  }).join("");
  const rows = dates.slice().reverse().map((row) => `<tr><td>${escape(row.date)}</td><td class="num">${quantity(row.sales)}</td><td class="num">${quantity(row.rank)}</td><td class="num">${money(row.price)}</td></tr>`);
  container.innerHTML = `<div class="selection-trend" aria-hidden="true">${salesBars}</div><div class="table-scroll" tabindex="0" aria-label="排名与销量历史"><table><thead><tr><th>日期</th><th class="num">销量</th><th class="num">排名</th><th class="num">价格</th></tr></thead><tbody>${rows.join("") || tableMessage("还没有历史记录", 4)}</tbody></table></div>`;
}

function detailRows(variants) {
  const availabilityNames = { IN_STOCK: "有货", OUT_OF_STOCK: "缺货", UNKNOWN: "待核对" };
  return variants.map((variant) => {
    const availability = availabilityNames[variant.availability] || variant.availability || "待核对";
    return `<tr><td>${escape(variant.color || "—")}</td><td>${escape(variant.size || "—")}</td>
      <td>${externalLink(itemUrl(variant.itemId), variant.itemId) || "—"}</td>
      <td class="num">${quantity(variant.previousQuantity)}</td><td class="num">${quantity(variant.currentQuantity)}</td>
      <td class="num">${variant.comparable ? quantity(variant.sales) : "—"}</td>
      <td>${Number(variant.stockIncrease) > 0 ? `<span class="selection-stock-tag">库存上涨 +${quantity(variant.stockIncrease)}</span>` : escape(variant.comparable ? availability : `不可比 · ${availability}`)}
      <small class="selection-cell-note">${escape([variant.sellerName || variant.sellerId, variant.fulfillment, variant.issue].filter(Boolean).join(" · "))}</small></td></tr>`;
  }).join("");
}

function csvCell(value) {
  let text = String(value ?? "");
  if (typeof value === "string" && /^[\t\r\n ]*[=+@\-\t\r\n]/.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}

export function createSelectionCenter({ notify = () => {} } = {}) {
  const find = (id) => document.getElementById(id);
  const page = find("view-selection-center");
  const date = find("selectionDate");
  const category = find("selectionCategory");
  const brand = find("selectionBrand");
  const sort = find("selectionSort");
  const search = find("selectionSearch");
  const increases = find("selectionIncreases");
  const watched = find("selectionWatched");
  const table = find("selectionRows");
  const summary = find("selectionSummary");
  const detail = find("selectionDetail");
  const detailBody = find("selectionDetailContent");
  let snapshot = null;
  let currentPage = 1;
  let listRequest = 0;
  let detailRequest = 0;
  let loading = false;
  let searchTimer = null;

  function filteredItems() {
    const query = search.value.trim().toLocaleLowerCase();
    const byCategory = Boolean(category.value);
    const items = (snapshot?.items || []).filter((item) => {
      if (byCategory && item.category !== category.value) return false;
      if (brand.value && item.brand !== brand.value) return false;
      if (increases.checked && !(Number(item.stockIncreaseSkus) > 0)) return false;
      if (watched.checked && !item.watched) return false;
      return !query || [item.itemId, item.title, item.brand].some((value) => String(value ?? "").toLocaleLowerCase().includes(query));
    });
    const rankOf = (item) => byCategory ? item.categoryRank : item.rank;
    const candidates = snapshot?.status === "RANKED"
      ? items.filter((item) => rankOf(item) > 0 && rankOf(item) <= 100)
      : items.slice();
    const sortMode = sort?.value || "rank";
    return candidates.sort((first, second) => {
      if (sortMode === "sales") return (Number(second.sales) || 0) - (Number(first.sales) || 0) || (rankOf(first) || 999) - (rankOf(second) || 999);
      if (sortMode === "sevenDaySales") return (Number(second.sevenDaySales) || 0) - (Number(first.sevenDaySales) || 0) || (rankOf(first) || 999) - (rankOf(second) || 999);
      if (sortMode === "stock") return (Number(second.currentStockTotal ?? -1)) - (Number(first.currentStockTotal ?? -1)) || (rankOf(first) || 999) - (rankOf(second) || 999);
      if (sortMode === "priceAsc") return (Number(first.priceMin ?? first.price) || 0) - (Number(second.priceMin ?? second.price) || 0);
      if (sortMode === "priceDesc") return (Number(second.priceMin ?? second.price) || 0) - (Number(first.priceMin ?? first.price) || 0);
      return (rankOf(first) || 999) - (rankOf(second) || 999);
    });
  }

  function updatePagination(total, numPages) {
    find("selectionPageText").textContent = total ? `共 ${quantity(total)} 款 · 第 ${currentPage} / ${numPages} 页` : "没有匹配的款式";
    find("selectionPrevious").disabled = loading || currentPage <= 1;
    find("selectionNext").disabled = loading || currentPage >= numPages;
    find("selectionExport").disabled = loading || total <= 0;
  }

  function watchImageErrors(node) {
    node.querySelectorAll("img.selection-thumb").forEach((img) => {
      const showFallback = () => {
        const fallback = document.createElement("span");
        fallback.className = "selection-thumb";
        fallback.textContent = "暂无图片";
        img.replaceWith(fallback);
      };
      img.addEventListener("error", showFallback, { once: true });
      if (img.complete && !img.naturalWidth) showFallback();
    });
  }

  function renderTable() {
    if (loading || !snapshot) return;
    const rows = filteredItems();
    const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
    currentPage = Math.max(1, Math.min(currentPage, pages));
    const start = (currentPage - 1) * PAGE_SIZE;
    const visible = rows.slice(start, start + PAGE_SIZE);
    const empty = snapshot.status === "EMPTY" ? "等待第一批候选池录入" : "没有符合筛选条件的款式";
    table.innerHTML = visible.map((item) => itemRow(item, Boolean(category.value))).join("") || tableMessage(empty, BOARD_COLUMNS);
    watchImageErrors(table);
    updatePagination(rows.length, pages);
  }

  function renderSummary(element, data) {
    const labels = {
      EMPTY: "等待首批录入",
      BASELINE: "候选池基线 · 两天可比库存齐备后出榜",
      PARTIAL: "采集未完成 · 本期候选款仅供查看",
      RANKED: "每日销量榜",
    };
    const oldBoard = data.status === "RANKED" && data.latestDate && data.date < data.latestDate && !date.value;
    element.dataset.tone = data.status === "PARTIAL" || oldBoard ? "attention" : "neutral";
    const parts = [`<strong>${escape(labels[data.status] || "采集记录")}</strong>`];
    if (data.date) parts.push(`<span>批次日期 ${escape(data.date)}</span>`);
    if (data.zip) parts.push(`<span>邮编 ${escape(data.zip)}</span>`);
    if (data.expectedPages) parts.push(`<span>已采页 ${quantity(data.completedPages)} / ${quantity(data.expectedPages)}</span>`);
    if (data.candidates != null) parts.push(`<span>候选 ${quantity(data.candidates)} 款</span>`);
    if (data.status === "RANKED") parts.push(`<span>可比 ${quantity(data.comparableStyles)} 款</span>`);
    if (data.observedAt) parts.push(`<span>采集时间 ${escape(dateText(data.observedAt))}（北京时间）</span>`);
    if (data.status === "BASELINE" && data.inventoryComplete === false) parts.push("<span>库存快照待补齐</span>");
    if (data.issue) parts.push(`<span>${escape(data.issue)}</span>`);
    if (oldBoard) parts.push("<span>最新采集日尚未发布，继续显示最近一期榜单</span>");
    parts.push(externalLink(walmartUrl(data.sourceUrl), "Walmart 新品页"));
    element.innerHTML = parts.join("");
  }

  async function load() {
    const id = ++listRequest;
    loading = true;
    table.setAttribute("aria-busy", "true");
    summary.dataset.tone = "neutral";
    summary.textContent = "正在读取新品榜单…";
    table.innerHTML = tableMessage("正在读取候选款", BOARD_COLUMNS);
    updatePagination(0, 1);
    try {
      const result = await request("", { date: date.value });
      if (id !== listRequest) return;
      snapshot = result;
      replaceOptions(date, "最新一期", [...new Set(result.availableDates || [])].sort().reverse());
      const choices = (key) => [...new Set((result.items || []).map((item) => item[key]).filter(Boolean))].sort((a, b) => a.localeCompare(b, "zh-CN"));
      replaceOptions(category, "全部分类", choices("category"));
      replaceOptions(brand, "全部品牌", choices("brand"));
      currentPage = 1;
      renderSummary(summary, result);
    } catch (error) {
      if (id !== listRequest) return;
      snapshot = null;
      summary.dataset.tone = "attention";
      summary.replaceChildren(document.createTextNode(`新品榜单读取失败 · ${error.message}`));
      const retry = document.createElement("button");
      retry.type = "button";
      retry.className = "secondary-button";
      retry.textContent = "重试";
      retry.addEventListener("click", load);
      summary.append(retry);
      table.innerHTML = tableMessage("榜单暂不可用", BOARD_COLUMNS);
    } finally {
      if (id === listRequest) {
        loading = false;
        table.setAttribute("aria-busy", "false");
        renderTable();
      }
    }
  }

  async function openDetail(id, focusIncrease = false) {
    const item = snapshot?.items?.find((row) => String(row.itemId) === id);
    if (!item) return;
    const at = snapshot.date;
    const seq = ++detailRequest;
    find("selectionDetailTitle").textContent = item.title || "款式详情";
    find("selectionDetailMeta").textContent = `代表 Item ID ${item.itemId} · ${at ?? ""}`;
    detailBody.textContent = "正在读取款式详情…";
    if (!detail.open) detail.showModal();
    try {
      const payload = await request(`/items/${encodeURIComponent(id)}`, { date: at });
      if (seq !== detailRequest || !detail.open) return;
      const current = payload.item || item;
      const variants = payload.variants || [];
      const history = payload.history || [];
      const firstSeen = current.firstSeen || current.firstSeenDate;
      detailBody.innerHTML = `<div class="selection-detail-identity">${escape(current.brand || "")}${current.category ? ` · ${escape(current.category)}` : ""}
        <span>${current.groupVerified ? "父子体归属已核对" : "父子体归属待核对"}</span>${firstSeen ? `<span>首次发现 ${escape(firstSeen)}</span>` : ""}${externalLink(itemUrl(current.itemId), "查看沃尔玛")}</div>
        <section class="selection-detail-section"><div class="selection-detail-heading"><h3>销量、排名与价格</h3><div class="selection-trend-controls" aria-label="查看趋势周期"><button type="button" class="secondary-button" data-days="7" aria-pressed="true">近 7 日</button><button type="button" class="secondary-button" data-days="30" aria-pressed="false">近 30 日</button></div></div><div id="selectionDetailTrend"></div></section>
        <section class="selection-detail-section" id="selectionVariantSection" tabindex="-1"><div class="selection-detail-heading"><h3>颜色尺码库存</h3><label class="selection-check"><input id="selectionDetailIncreaseOnly" type="checkbox" ${focusIncrease ? "checked" : ""}>仅库存上涨</label></div>
        <p>可比 SKU ${quantity(current.comparableSkus)} / ${quantity(current.expectedSkus)}。库存上涨单独标记。</p>
        <div class="table-scroll" tabindex="0" aria-label="颜色尺码库存明细"><table><thead><tr><th>颜色</th><th>尺码</th><th>子体 Item ID</th><th class="num">昨日可售</th><th class="num">今日可售</th><th class="num">计入销量</th><th>状态</th></tr></thead><tbody id="selectionVariantRows"></tbody></table></div></section>`;
      const trend = find("selectionDetailTrend");
      const increaseOnly = find("selectionDetailIncreaseOnly");
      const updateVariants = () => {
        const displayed = increaseOnly.checked ? variants.filter((variant) => Number(variant.stockIncrease) > 0) : variants;
        find("selectionVariantRows").innerHTML = detailRows(displayed) || tableMessage(increaseOnly.checked ? "没有可比的上涨 SKU" : "待录入颜色尺码库存", 7);
      };
      updateVariants();
      increaseOnly.addEventListener("change", updateVariants);
      renderTrend(trend, history, at, 7);
      detailBody.querySelectorAll("[data-days]").forEach((button) => button.addEventListener("click", () => {
        detailBody.querySelectorAll("[data-days]").forEach((peer) => peer.setAttribute("aria-pressed", String(peer === button)));
        renderTrend(trend, history, at, Number(button.dataset.days));
      }));
      if (focusIncrease) {
        find("selectionVariantSection").focus({ preventScroll: true });
        find("selectionVariantSection").scrollIntoView({ block: "start" });
      }
    } catch (error) {
      if (seq === detailRequest && detail.open) detailBody.textContent = `款式详情读取失败 · ${error.message}`;
    }
  }

  async function toggleWatch(id, button) {
    const item = snapshot?.items?.find((row) => String(row.itemId) === id);
    if (!item) return;
    button.disabled = true;
    const next = !item.watched;
    try {
      await request(`/items/${encodeURIComponent(id)}/watch`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ watched: next }),
      });
      item.watched = next;
      if (watched.checked && !next) renderTable();
      else {
        button.setAttribute("aria-pressed", String(next));
        button.textContent = next ? "已关注" : "关注";
      }
    } catch (error) {
      notify(error.message || "关注状态更新失败");
    } finally {
      button.disabled = false;
    }
  }

  function exportCsv() {
    const columns = ["日期", "排名", "Item ID", "主图", "标题", "分类", "品牌", "价格", "当前库存（沃尔玛单笔可售上限合计）",
      "库存已采 SKU 数", "库存应采 SKU 数", "库存采集状态", "销量", "近7日销量", "库存上涨 SKU 数", "库存上涨件数", "沃尔玛链接"];
    const values = filteredItems().map((item) => {
      const stock = currentStock(item);
      return [snapshot.date, category.value ? item.categoryRank : item.rank, item.itemId, walmartUrl(item.imageUrl), item.title,
        item.category, item.brand, item.price, stock.total, stock.known, stock.expected, stock.status,
        item.sales, item.sevenDaySales, item.stockIncreaseSkus, item.stockIncreaseUnits, itemUrl(item.itemId)];
    });
    const data = [columns, ...values].map((row) => row.map(csvCell).join(",")).join("\r\n");
    const file = URL.createObjectURL(new Blob(["\ufeff", data], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.download = `Walmart-新品-${snapshot.date || "snapshot"}.csv`;
    anchor.href = file;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(file), 2000);
  }

  async function loadHistoryStatus() {
    const historyStatus = find("selectionHistoryStatus");
    const refresh = find("refreshSelectionStatus");
    if (refresh.disabled) return;
    refresh.disabled = true;
    historyStatus.dataset.tone = "neutral";
    historyStatus.textContent = "正在读取采集状态…";
    try {
      const result = await request();
      if (!result.latestDate || result.latestDate === result.date) {
        renderSummary(historyStatus, result);
      } else {
        historyStatus.dataset.tone = "attention";
        const pages = result.latestExpectedPages
          ? `<span>已采页 ${quantity(result.latestCompletedPages)} / ${quantity(result.latestExpectedPages)}</span>` : "";
        const phase = result.latestComplete ? "候选池已录入" : "最近批次尚未采全";
        historyStatus.innerHTML = `<strong>${phase}</strong><span>批次日期 ${escape(result.latestDate)}</span>${pages}
          ${result.latestObservedAt ? `<span>采集时间 ${escape(dateText(result.latestObservedAt))}（北京时间）</span>` : ""}
          <span>榜单日期 ${escape(result.date || "未发布")}</span>${result.latestIssue ? `<span>${escape(result.latestIssue)}</span>` : ""}`;
      }
    } catch (error) {
      historyStatus.dataset.tone = "attention";
      historyStatus.textContent = `采集状态读取失败 · ${error.message}`;
    } finally {
      refresh.disabled = false;
    }
  }

  async function importSnapshot(event) {
    event.preventDefault();
    const input = find("selectionImportFile");
    const button = find("selectionImportButton");
    const info = find("selectionImportStatus");
    const file = input.files?.[0];
    if (!file || button.disabled) return;
    info.hidden = false;
    info.dataset.tone = "neutral";
    info.textContent = "正在导入快照…";
    button.disabled = true;
    try {
      const raw = await file.text();
      let payload;
      try {
        payload = JSON.parse(raw.replace(/^\ufeff/, ""));
      } catch {
        throw new Error("文件不是有效的 JSON 快照");
      }
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        throw new Error("快照文件应包含一个采集批次");
      }
      const result = await request("/snapshots", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
      });
      const stage = result.duplicate ? "此快照已录入" : result.published ? "快照已录入并发布" : "快照已录入";
      info.textContent = `${stage} · ${result.date || ""} · 候选 ${quantity(result.candidates)} 款`;
      input.value = "";
      if (page.classList.contains("active")) load();
      if (!find("dataCenterHistoryPanel").hidden) loadHistoryStatus();
    } catch (error) {
      info.dataset.tone = "attention";
      info.textContent = `导入失败 · ${error.message}`;
    } finally {
      button.disabled = false;
    }
  }

  page.addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (!button) return;
    if (button.dataset.selectionItem) openDetail(button.dataset.selectionItem);
    else if (button.dataset.selectionIncrease) openDetail(button.dataset.selectionIncrease, true);
    else if (button.dataset.selectionWatch) toggleWatch(button.dataset.selectionWatch, button);
  });
  for (const filter of [category, brand, sort, increases, watched].filter(Boolean)) {
    filter.addEventListener("change", () => { currentPage = 1; renderTable(); });
  }
  search.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { currentPage = 1; renderTable(); }, 180);
  });
  date.addEventListener("change", load);
  detail.addEventListener("close", () => { detailRequest++; });
  find("selectionPrevious").addEventListener("click", () => { currentPage--; renderTable(); });
  find("selectionNext").addEventListener("click", () => { currentPage++; renderTable(); });
  find("selectionExport").addEventListener("click", exportCsv);
  find("selectionImportForm").addEventListener("submit", importSnapshot);
  find("refreshSelectionStatus").addEventListener("click", loadHistoryStatus);

  return { load, loadHistoryStatus };
}
