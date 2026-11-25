const API = "/api/finance";

const INT_FMT = new Intl.NumberFormat("zh-CN");
const USD_FMT = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const CNY_FMT = new Intl.NumberFormat("zh-CN", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const CNY_INT_FMT = new Intl.NumberFormat("zh-CN", {
  maximumFractionDigits: 0,
});

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[ch]);
}

function num(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function usd(value) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  return USD_FMT.format(Number(value));
}

function cny(value, compact = false) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const n = Number(value);
  const abs = Math.abs(n);
  const sign = n < 0 ? "-" : "";
  if (compact && abs >= 10000) {
    return `${sign}¥${(abs / 10000).toFixed(2)}万`;
  }
  return `${sign}¥${CNY_FMT.format(abs)}`;
}

function cnyShort(value) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const n = Number(value);
  const abs = Math.abs(n);
  const sign = n < 0 ? "-" : "";
  return `${sign}¥${CNY_INT_FMT.format(Math.round(abs))}`;
}

function pct(value, digits = 1) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  return `${(Number(value) * 100).toFixed(digits)}%`;
}

function intText(value) {
  return INT_FMT.format(Math.round(num(value)));
}

function safeImageUrl(value) {
  if (!value) return "";
  try {
    const parsed = new URL(value);
    if (parsed.protocol === "https:" || parsed.protocol === "http:") return parsed.href;
  } catch {
    // ignore malformed URLs
  }
  return "";
}

async function fetchJson(path, { signal, method = "GET", body } = {}) {
  const options = { cache: "no-store", signal, method };
  if (body != null) {
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(body);
  }
  const response = await fetch(`${API}${path}`, options);
  const payload = await response.json().catch(() => null);
  if (!response.ok || !payload || payload.success === false) {
    throw new Error(payload?.message || `财务数据请求失败（${response.status}）`);
  }
  return payload.data;
}

function deltaBadge(current, previous, mode = "percent") {
  if (previous == null) return "";
  const cur = num(current);
  const prev = num(previous);
  if (mode === "percent") {
    if (Math.abs(prev) < 0.01) return "";
    const ratio = (cur - prev) / Math.abs(prev);
    const cls = ratio > 0.001 ? "up" : ratio < -0.001 ? "down" : "flat";
    const arrow = ratio > 0.001 ? "+" : "";
    return `<span class="finance-delta ${cls}">环比 ${arrow}${(ratio * 100).toFixed(1)}%</span>`;
  }
  const diff = cur - prev;
  const cls = diff > 0.01 ? "up" : diff < -0.01 ? "down" : "flat";
  const sign = diff > 0 ? "+" : "";
  if (mode === "pp") {
    return `<span class="finance-delta ${cls}">${sign}${(diff * 100).toFixed(1)}pp</span>`;
  }
  return `<span class="finance-delta ${cls}">环比 ${sign}${cnyShort(diff)}</span>`;
}

function svgHatchDefs(prefix) {
  return `
    <defs>
      <pattern id="${prefix}HatchBlue" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="6" height="6" fill="rgba(22,104,214,0.10)"/>
        <line x1="0" y1="0" x2="0" y2="6" stroke="rgba(22,104,214,0.42)" stroke-width="2.2"/>
      </pattern>
      <pattern id="${prefix}HatchGreen" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="6" height="6" fill="rgba(11,125,95,0.18)"/>
        <line x1="0" y1="0" x2="0" y2="6" stroke="#0b7d5f" stroke-width="2.4"/>
      </pattern>
      <pattern id="${prefix}HatchRed" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="6" height="6" fill="rgba(208,52,58,0.18)"/>
        <line x1="0" y1="0" x2="0" y2="6" stroke="#d0343a" stroke-width="2.4"/>
      </pattern>
    </defs>
  `;
}

function sourcePillHtml(tag, show) {
  if (!show || !tag || tag === "财务正式") return "";
  let cls = "manual";
  if (tag.includes("缺失") || tag.includes("未齐")) cls = "missing";
  else if (tag.includes("预估") || tag.includes("推算") || tag.includes("部分")) cls = "est";
  else if (tag.includes("领星") || tag.includes("实测") || tag.includes("实扣")) cls = tag.includes("WFS") ? "wfs" : "actual";
  return `<span class="finance-src-pill ${cls}">${escapeHtml(tag)}</span>`;
}

function dataCoverage(label, status, cutoff, wfs = false, coveredDays, totalDays) {
  const tone = status === "实测" ? (wfs ? "wfs" : "actual") : (status === "缺数据" ? "missing" : "est");
  const date = cutoff ? ` · 截至 ${cutoff}` : "";
  const days = coveredDays != null && totalDays ? ` · 覆盖 ${coveredDays}/${totalDays} 天` : "";
  return `<li>${escapeHtml(label)}<span class="finance-src-pill ${tone}">${escapeHtml(status)}</span><span class="finance-coverage-date">${escapeHtml(date + days)}</span></li>`;
}

function observedCoverage(cutoff, start, end, coveredDays, totalDays) {
  if (coveredDays != null && totalDays) {
    return coveredDays <= 0 ? "缺数据" : (coveredDays < totalDays ? "部分同步" : "实测");
  }
  if (!cutoff || (start && cutoff < start)) return "缺数据";
  return end && cutoff < end ? "部分同步" : "实测";
}

function wfsCoverage(coveredDays, totalDays, actualUsed) {
  if (coveredDays > 0 && coveredDays < totalDays) return "实扣 + 预估";
  return (coveredDays != null && coveredDays === totalDays && totalDays > 0) || actualUsed ? "实测" : "费率预估";
}

function basisText(value) {
  return ({
    FINANCE_HIST: "历史财务",
    PROFIT_ASSUMPTION: "手工采购/头程",
    STORE_FALLBACK: "店铺均值",
    FINANCE_STORE_MIX: "款级 + 店铺历史",
    MANUAL: "手工退款率",
    MANUAL_FALLBACK: "手工退款率",
    SYSTEM: "系统退款率",
    CUMULATIVE: "系统累计退款率",
    ITEM_PROMOTION: "已确认活动佣金",
    ITEM_PROMOTION_MIX: "活动 + 标准佣金",
    STANDARD_TIER: "标准阶梯",
    NO_SALES: "无销量",
    SKU_ASSUMPTION: "工作台设定",
  })[value] || "";
}

function parseCommissionRules(text) {
  return text.trim().split(/\r?\n/).filter((line) => line.trim()).map((line, index) => {
    const fields = line.trim().split(/[\t,，]/).map((field) => field.trim());
    const [itemId, startDate, endDate, price, rateText] = fields;
    const validDate = (value) => /^\d{4}-\d{2}-\d{2}$/.test(value)
      && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
    const maximumPriceUsd = Number(price);
    const isPercent = rateText?.endsWith("%");
    const rateValue = isPercent ? rateText.slice(0, -1) : rateText;
    const rate = Number(rateValue) / (isPercent ? 100 : 1);
    const decimal = /^\d+(\.\d+)?$/;
    if (fields.length !== 5 || !/^\d+$/.test(itemId) || !validDate(startDate) || !validDate(endDate)
        || endDate < startDate || !decimal.test(price) || !Number.isFinite(maximumPriceUsd) || maximumPriceUsd <= 0
        || !decimal.test(rateValue) || !Number.isFinite(rate) || rate < 0 || rate > 1) {
      throw new Error(`第 ${index + 1} 条格式有误。请填写 Item ID、开始日期、结束日期、最高成交价和佣金率（例如 2%）。`);
    }
    return { itemId, startDate, endDate, maximumPriceUsd, rate };
  });
}

export function createFinanceView({ notify = () => {}, openInventory } = {}) {
  const state = {
    data: null,
    overviewRequest: null,
    drawerRequest: null,
    appliedEstimateParams: null,
    promotionRules: null,
    pendingPromotionRules: [],
    savingPromotionRules: false,
    startMonth: null,
    endMonth: null,
    includeEstimate: true,
    customStartDate: null,
    customEndDate: null,
    overrideExchangeRate: null,
    overrideOffsiteFeeCny: null,
    activePreset: "latest",
    chartMode: "all", // "all" | "range"
    profitFilter: "all", // "all" | "profit" | "loss"
    hideClearance: false,
    categoryFilter: "",
    seasonFilter: "",
    statusFilter: "",
    search: "",
    sortKey: "profitCny",
    sortDir: "desc",
    initialized: false,
  };

  const el = (id) => document.getElementById(id);

  function clearEstimateOverrides() {
    state.customStartDate = null;
    state.customEndDate = null;
    state.overrideExchangeRate = null;
    state.overrideOffsiteFeeCny = null;
  }

  function estimateRequestParams() {
    const params = new URLSearchParams({ includeEstimate: String(state.includeEstimate) });
    if (state.customStartDate && state.customEndDate) {
      params.set("customStartDate", state.customStartDate);
      params.set("customEndDate", state.customEndDate);
    }
    if (state.overrideExchangeRate) params.set("exchangeRate", state.overrideExchangeRate);
    if (state.overrideOffsiteFeeCny != null) params.set("offsiteFeeCny", state.overrideOffsiteFeeCny);
    return params;
  }

  function showValidationMessage(input, status, message = "") {
    if (!input) return;
    input.setCustomValidity(message);
    input.setAttribute("aria-invalid", String(Boolean(message)));
    if (status) status.textContent = message;
  }

  function bindEventsOnce() {
    if (state.initialized) return;
    state.initialized = true;

    el("financeIncludeEstimate")?.addEventListener("change", (event) => {
      state.includeEstimate = event.target.checked;
      clearEstimateOverrides();
      if (!state.includeEstimate && state.activePreset.startsWith("est:")) {
        state.activePreset = "latest";
        const lastFinal = state.data?.meta?.latestFinalMonth || null;
        loadRange(lastFinal, lastFinal);
      } else {
        loadRange(state.startMonth, state.endMonth);
      }
    });

    el("financeStartMonth")?.addEventListener("change", () => {
      state.activePreset = "custom";
      clearEstimateOverrides();
      state.startMonth = el("financeStartMonth").value;
      state.endMonth = el("financeEndMonth").value;
      loadRange(state.startMonth, state.endMonth);
    });

    el("financeEndMonth")?.addEventListener("change", () => {
      state.activePreset = "custom";
      clearEstimateOverrides();
      state.startMonth = el("financeStartMonth").value;
      state.endMonth = el("financeEndMonth").value;
      loadRange(state.startMonth, state.endMonth);
    });

    el("financePresets")?.addEventListener("click", (event) => {
      const btn = event.target.closest("[data-fin-preset]");
      if (!btn || !state.data?.meta?.availableMonths?.length) return;
      applyPreset(btn.dataset.finPreset);
    });

    const estimateBar = el("financeEstimateBar");
    estimateBar?.addEventListener("input", () => showValidationMessage(el("financeEstEndDate"), el("financeEstimateStatus")));
    estimateBar?.addEventListener("submit", (event) => {
      event.preventDefault();
      const start = el("financeEstStartDate");
      const end = el("financeEstEndDate");
      if (start && end && end.value < start.value) {
        showValidationMessage(end, el("financeEstimateStatus"), "结束日期不能早于开始日期");
        end.reportValidity();
        return;
      }
      state.customStartDate = start?.value || null;
      state.customEndDate = end?.value || null;
      state.overrideExchangeRate = el("financeEstFx")?.value || null;
      state.overrideOffsiteFeeCny = el("financeEstOffsite")?.value || null;
      loadRange(state.startMonth, state.endMonth);
    });
    estimateBar?.addEventListener("click", (event) => {
      if (event.target.closest("#financeEstReset")) {
        clearEstimateOverrides();
        loadRange(state.startMonth, state.endMonth);
      }
    });

    el("financeChartMode")?.addEventListener("click", (event) => {
      const btn = event.target.closest("[data-chart-mode]");
      if (!btn) return;
      state.chartMode = btn.dataset.chartMode;
      renderTrendChart();
    });

    el("financeProfitFilter")?.addEventListener("click", (event) => {
      const btn = event.target.closest("[data-profit-filter]");
      if (!btn) return;
      state.profitFilter = btn.dataset.profitFilter;
      renderStyleTable();
    });

    el("financeToggleClearance")?.addEventListener("click", () => {
      state.hideClearance = !state.hideClearance;
      if (state.hideClearance && isClearanceStatus(state.statusFilter)) {
        state.statusFilter = "";
        const sel = el("financeStatusFilter");
        if (sel) sel.value = "";
      }
      renderStyleTable();
    });

    el("financeCategoryFilter")?.addEventListener("change", (event) => {
      state.categoryFilter = event.target.value;
      renderStyleTable();
    });

    el("financeSeasonFilter")?.addEventListener("change", (event) => {
      state.seasonFilter = event.target.value;
      renderStyleTable();
    });

    el("financeStatusFilter")?.addEventListener("change", (event) => {
      state.statusFilter = event.target.value;
      if (isClearanceStatus(state.statusFilter) && state.hideClearance) {
        state.hideClearance = false;
      }
      renderStyleTable();
    });

    el("financeStyleSearch")?.addEventListener("input", (event) => {
      state.search = event.target.value.trim();
      renderStyleTable();
    });

    el("financeStyleHead")?.addEventListener("click", (event) => {
      const th = event.target.closest("th[data-sort]");
      if (!th) return;
      const key = th.dataset.sort;
      if (state.sortKey === key) {
        state.sortDir = state.sortDir === "desc" ? "asc" : "desc";
      } else {
        state.sortKey = key;
        state.sortDir = key === "parentSku" ? "asc" : "desc";
      }
      renderStyleTable();
    });

    el("financeStyleBody")?.addEventListener("click", (event) => {
      const trigger = event.target.closest("[data-style-sku]");
      if (!trigger) return;
      openStyleDrawer(trigger.dataset.styleSku);
    });

    const drawer = el("financeStyleDrawer");
    drawer?.addEventListener("click", (event) => {
      const inventoryTrigger = event.target.closest("[data-finance-inventory]");
      if (inventoryTrigger && openInventory) {
        drawer.close();
        openInventory(inventoryTrigger.dataset.financeInventory);
        return;
      }
      if (event.target === drawer) drawer.close();
    });
    drawer?.addEventListener("close", () => {
      if (!drawer.open) state.drawerRequest?.abort();
    });
    bindPromotionRules();
  }

  function bindPromotionRules() {
    const panel = el("financePromotionRules");
    const input = el("financePromotionInput");
    const preview = el("financePromotionPreview");
    const save = el("financePromotionSave");
    const status = el("financePromotionStatus");
    const confirmDelete = el("financePromotionDelete");
    if (!panel || !input || !preview || !save || !status) return;
    let deletion = null;

    const clearPreview = () => {
      state.pendingPromotionRules = [];
      preview.hidden = true;
      preview.innerHTML = "";
      save.hidden = true;
    };
    input.addEventListener("input", () => {
      showValidationMessage(input, status);
      clearPreview();
    });
    panel.addEventListener("toggle", async () => {
      if (!panel.open || state.promotionRules !== null) return;
      status.textContent = "正在读取已录入的减佣规则…";
      try {
        const rules = await fetchJson("/commission-rules");
        if (state.promotionRules === null) {
          state.promotionRules = rules;
          renderPromotionRules();
          status.textContent = "";
        }
      } catch (error) {
        status.textContent = error.message;
      }
    });
    el("financePromotionForm")?.addEventListener("submit", (event) => {
      event.preventDefault();
      try {
        state.pendingPromotionRules = parseCommissionRules(input.value);
        if (!state.pendingPromotionRules.length) throw new Error("请至少填写一条已确认的活动规则。");
        const uniqueRules = new Set(state.pendingPromotionRules.map((rule) =>
          `${rule.itemId}|${rule.startDate}|${rule.endDate}`));
        if (uniqueRules.size !== state.pendingPromotionRules.length) throw new Error("同一批中存在重复的 Item ID 和有效期，请保留一条。");
      } catch (error) {
        clearPreview();
        showValidationMessage(input, status, error.message);
        input.reportValidity();
        return;
      }
      const rows = state.pendingPromotionRules;
      const shortList = rows.slice(0, 5).map((rule) =>
        `<li>${escapeHtml(rule.itemId)} · ${escapeHtml(rule.startDate)} 至 ${escapeHtml(rule.endDate)} · ≤ ${usd(rule.maximumPriceUsd)} · ${pct(rule.rate, 2)}</li>`).join("");
      preview.innerHTML = `<p>待保存 ${intText(rows.length)} 条${rows.length > 5 ? "（前 5 条）" : ""}。同 Item 和有效期的规则将更新。</p><ul>${shortList}</ul>`;
      preview.hidden = false;
      save.textContent = `保存 ${intText(rows.length)} 条规则`;
      save.hidden = false;
      status.textContent = "";
    });
    save.addEventListener("click", async () => {
      if (state.savingPromotionRules || !state.pendingPromotionRules.length) return;
      state.savingPromotionRules = true;
      save.disabled = true;
      input.readOnly = true;
      const rules = state.pendingPromotionRules;
      try {
        state.promotionRules = await fetchJson("/commission-rules", { method: "POST", body: rules });
        input.value = "";
        clearPreview();
        renderPromotionRules();
        status.textContent = `已保存 ${intText(rules.length)} 条，正在按规则重算预估。`;
        loadRange(state.startMonth, state.endMonth);
      } catch (error) {
        status.textContent = error.message;
      } finally {
        state.savingPromotionRules = false;
        save.disabled = false;
        input.readOnly = false;
      }
    });
    el("financePromotionSaved")?.addEventListener("click", (event) => {
      const trigger = event.target.closest("[data-fin-promotion-delete]");
      if (!trigger || state.savingPromotionRules || !confirmDelete || confirmDelete.open) return;
      const rule = state.promotionRules?.find(({ id }) => String(id) === trigger.dataset.finPromotionDelete);
      if (!rule) return;
      deletion = { rule, trigger };
      el("financePromotionDeleteMessage").textContent = `Item ${rule.itemId} · ${rule.startDate} 至 ${rule.endDate}`;
      confirmDelete.showModal();
    });
    confirmDelete?.addEventListener("close", async () => {
      const confirmed = confirmDelete.returnValue === "delete";
      confirmDelete.returnValue = "";
      const selected = deletion;
      deletion = null;
      if (!confirmed || !selected || state.savingPromotionRules) return;
      const { rule, trigger } = selected;
      state.savingPromotionRules = true;
      trigger.disabled = true;
      try {
        state.promotionRules = await fetchJson(`/commission-rules/${encodeURIComponent(rule.id)}`, { method: "DELETE" });
        renderPromotionRules();
        status.textContent = "已删除规则，正在重算预估。";
        loadRange(state.startMonth, state.endMonth);
      } catch (error) {
        status.textContent = error.message;
        trigger.disabled = false;
      } finally {
        state.savingPromotionRules = false;
      }
    });
  }

  function renderPromotionRules() {
    const target = el("financePromotionSaved");
    const rules = state.promotionRules || [];
    if (!target) return;
    target.innerHTML = rules.length ? `
      <div class="finance-table-scroll">
        <table class="finance-table">
          <caption>已录入的活动减佣规则</caption>
          <thead><tr><th scope="col">Item ID</th><th scope="col">有效期</th><th scope="col">最高成交价</th><th scope="col">佣金率</th><th scope="col">操作</th></tr></thead>
          <tbody>${rules.map((rule) => `<tr><th scope="row" class="mono">${escapeHtml(rule.itemId)}</th>
            <td>${escapeHtml(rule.startDate)} 至 ${escapeHtml(rule.endDate)}</td>
            <td>${usd(rule.maximumPriceUsd)}</td><td>${pct(rule.rate, 2)}</td>
            <td><button class="finance-est-reset" type="button" data-fin-promotion-delete="${escapeHtml(rule.id)}" aria-label="删除 ${escapeHtml(rule.itemId)} ${escapeHtml(rule.startDate)} 至 ${escapeHtml(rule.endDate)} 的规则">删除</button></td>
          </tr>`).join("")}</tbody>
        </table>
      </div>
    ` : '<p class="finance-panel-sub">当前店铺暂无已录入的减佣规则。</p>';
  }

  function applyPreset(preset) {
    const meta = state.data?.meta;
    const months = meta?.availableMonths || [];
    if (!months.length) return;
    state.activePreset = preset;
    clearEstimateOverrides();

    const actualMonths = months.filter((m) => !m.estimated);
    const lastActual = (actualMonths[actualMonths.length - 1] || months[months.length - 1]).month;
    const lastAny = months[months.length - 1].month;
    const first = months[0].month;

    let start = lastActual;
    let end = lastActual;
    if (preset === "latest") {
      start = lastActual;
      end = lastActual;
    } else if (preset.startsWith("est:")) {
      const m = preset.slice(4);
      start = m;
      end = m;
    } else if (preset === "3m") {
      const pool = actualMonths.length ? actualMonths : months;
      start = pool[Math.max(0, pool.length - 3)].month;
      end = pool[pool.length - 1].month;
    } else if (preset === "6m") {
      const pool = actualMonths.length ? actualMonths : months;
      start = pool[Math.max(0, pool.length - 6)].month;
      end = pool[pool.length - 1].month;
    } else if (preset === "all") {
      start = first;
      end = lastAny;
    } else if (/^y(\d{4})$/.test(preset)) {
      const year = preset.slice(1);
      const yearMonths = months.filter((m) => m.month.startsWith(`${year}-`));
      if (yearMonths.length) {
        start = yearMonths[0].month;
        end = yearMonths[yearMonths.length - 1].month;
      }
    }
    loadRange(start, end);
  }

  async function loadRange(startMonth, endMonth) {
    state.overviewRequest?.abort();
    const request = new AbortController();
    state.overviewRequest = request;
    const estimateParams = estimateRequestParams();
    const params = new URLSearchParams(estimateParams);
    if (startMonth) params.set("startMonth", startMonth);
    if (endMonth) params.set("endMonth", endMonth);
    const scope = el("financeScopeMeta");
    scope?.setAttribute("aria-busy", "true");
    if (scope && !state.data) scope.textContent = "正在加载财务利润数据…";

    try {
      const data = await fetchJson(`/overview?${params}`, { signal: request.signal });
      if (request.signal.aborted) return;
      state.data = data;
      state.appliedEstimateParams = estimateParams;
      state.startMonth = data.meta?.selectedStartMonth || null;
      state.endMonth = data.meta?.selectedEndMonth || null;
      renderAll();
    } catch (error) {
      if (!request.signal.aborted) {
        const message = error.message || "加载财务利润数据失败";
        if (scope && !state.data) scope.textContent = message;
        notify(message);
      }
    } finally {
      if (state.overviewRequest === request) {
        state.overviewRequest = null;
        scope?.removeAttribute("aria-busy");
      }
    }
  }

  function renderAll() {
    const data = state.data;
    if (!data || !data.meta || !data.meta.availableMonths?.length) {
      renderEmptyView();
      return;
    }
    renderToolbar();
    renderEstimateBar();
    renderKpis();
    renderTrendChart();
    renderBreakdown();
    renderFilterOptions();
    renderStyleTable();
    updatePageMetaText();
  }

  function updatePageMetaText() {
    const meta = state.data?.meta;
    const pageMeta = document.getElementById("pageMeta");
    if (!meta || !pageMeta) return;
    if (document.getElementById("view-finance")?.classList.contains("active")) {
      const tag = meta.selectedAllEstimate ? "实时预估口径" : (meta.selectedHasEstimate ? "财务正式 + 实时预估" : "财务正式口径");
      const rangeText = meta.selectedStartMonth === meta.selectedEndMonth
        ? `账期 ${meta.selectedStartMonth}${meta.selectedSettlementPeriod ? `（${meta.selectedSettlementPeriod}）` : ""}`
        : `账期 ${meta.selectedStartMonth} 至 ${meta.selectedEndMonth}（共 ${meta.selectedMonthCount} 期）`;
      pageMeta.textContent = `${meta.accountCode} · ${tag} · ${rangeText}`;
    }
  }

  function renderEmptyView() {
    const accountBadge = el("financeAccountBadge");
    if (accountBadge) accountBadge.textContent = state.data?.meta?.accountCode || "";
    const scope = el("financeScopeMeta");
    if (scope) scope.textContent = "暂无可选账期";
    const kpiGrid = el("financeKpiGrid");
    if (kpiGrid) {
      kpiGrid.innerHTML = `<div class="finance-empty" style="grid-column:1/-1"><strong>暂无财务利润数据</strong>当前店铺工作区尚未导入月度财务利润表。</div>`;
    }
    if (el("financeEstimateBar")) el("financeEstimateBar").hidden = true;
    if (el("financeTrendChart")) el("financeTrendChart").innerHTML = '<div class="finance-empty">暂无月度趋势数据</div>';
    if (el("financeBreakdownBody")) el("financeBreakdownBody").innerHTML = '<div class="finance-empty">暂无损益数据</div>';
    if (el("financeStyleBody")) el("financeStyleBody").innerHTML = '<tr><td colspan="13" class="finance-empty">暂无款号利润明细</td></tr>';
    if (el("financeStyleFoot")) el("financeStyleFoot").replaceChildren();
    if (el("financeStyleBadges")) el("financeStyleBadges").replaceChildren();
    if (el("financeMissingNotice")) el("financeMissingNotice").hidden = true;
    if (el("financePresets")) el("financePresets").replaceChildren();
    if (el("financeStartMonth")) el("financeStartMonth").replaceChildren();
    if (el("financeEndMonth")) el("financeEndMonth").replaceChildren();
  }

  function renderToolbar() {
    const { meta, kpi } = state.data;
    const accountBadge = el("financeAccountBadge");
    if (accountBadge) accountBadge.textContent = [meta.accountCode, meta.companyName].filter(Boolean).join(" · ");
    const toggle = el("financeIncludeEstimate");
    if (toggle) toggle.checked = Boolean(meta.includeEstimate);

    const scopeEl = el("financeScopeMeta");
    if (scopeEl) {
      const isSingle = meta.selectedStartMonth === meta.selectedEndMonth;
      const modeTag = meta.selectedAllEstimate
        ? `<span class="finance-tag est">实时预估</span> `
        : (meta.selectedHasEstimate ? `<span class="finance-tag est">含预估</span> `
          : `<span class="finance-tag">财务正式</span> `);
      const rangeLabel = isSingle
        ? `${modeTag}账期 <strong>${escapeHtml(meta.selectedStartMonth)}</strong>${meta.selectedSettlementPeriod ? `（${escapeHtml(meta.selectedSettlementPeriod)}）` : ""}`
        : `${modeTag}账期 <strong>${escapeHtml(meta.selectedStartMonth)} 至 ${escapeHtml(meta.selectedEndMonth)}</strong> · 共 ${meta.selectedMonthCount} 期`;
      const fxText = kpi ? ` · ${isSingle ? "汇率" : "加权汇率"} <span class="mono">${num(kpi.avgExchangeRate).toFixed(4)}</span>` : "";
      const estCount = (meta.estimatedMonths || []).length;
      const estSummary = estCount > 0 ? ` + ${estCount} 期实时预估` : "";
      scopeEl.innerHTML = `${rangeLabel}${fxText} · 财务正式 ${meta.totalStoreMonths} 期${estSummary}`;
    }

    const presetsEl = el("financePresets");
    if (presetsEl) {
      const focusedPreset = presetsEl.contains(document.activeElement)
        ? document.activeElement.dataset.finPreset
        : undefined;
      const estOptions = (meta.availableMonths || []).filter((m) => m.estimated);
      const presetDefs = [
        { id: "latest", label: `最新正式(${(meta.latestFinalMonth || "").slice(5)}月)`, est: false },
        ...estOptions.map((m) => ({
          id: `est:${m.month}`,
          label: m.estimateStage === "IN_PROGRESS_ESTIMATE"
            ? `${m.month.slice(5)}月账期 · 进行中`
            : `${m.month.slice(5)}月账期 · 预估`,
          est: true,
        })),
        { id: "3m", label: "近3月正式", est: false },
        { id: "6m", label: "近6月正式", est: false },
        ...(meta.availableYears || []).map((y) => ({ id: `y${y}`, label: `${y}年`, est: false })),
        { id: "all", label: "全部", est: false },
      ];
      presetsEl.innerHTML = presetDefs
        .map((p) => {
          const active = state.activePreset === p.id;
          return `<button class="finance-preset-btn ${p.est ? "is-estimate" : ""} ${active ? "active" : ""}" type="button" data-fin-preset="${escapeHtml(p.id)}" aria-pressed="${active}">${escapeHtml(p.label)}</button>`;
        })
        .join("");
      if (focusedPreset) {
        Array.from(presetsEl.querySelectorAll("[data-fin-preset]"))
          .find((btn) => btn.dataset.finPreset === focusedPreset)?.focus({ preventScroll: true });
      }
    }

    const startSelect = el("financeStartMonth");
    const endSelect = el("financeEndMonth");
    if (startSelect && endSelect) {
      const buildOpts = (selected) => meta.availableMonths
        .slice()
        .reverse()
        .map((m) => {
          let suffix = "";
          if (m.estimated) {
            suffix = m.estimateStage === "IN_PROGRESS_ESTIMATE"
              ? ` · 进行中 (${m.settlementPeriod || ""})`
              : ` · 预估 (${m.settlementPeriod || ""})`;
          } else if (!m.hasStyleDetail) {
            suffix = " · 无单款";
          } else if (m.settlementPeriod) {
            suffix = ` (${m.settlementPeriod})`;
          }
          return `<option value="${escapeHtml(m.month)}" ${m.month === selected ? "selected" : ""}>${escapeHtml(m.month + suffix)}</option>`;
        })
        .join("");
      startSelect.innerHTML = buildOpts(meta.selectedStartMonth);
      endSelect.innerHTML = buildOpts(meta.selectedEndMonth);
    }
  }

  function renderEstimateBar() {
    const bar = el("financeEstimateBar");
    const ctx = state.data?.meta?.estimateContext;
    if (!bar) return;
    if (!ctx) {
      bar.hidden = true;
      bar.innerHTML = "";
      return;
    }

    bar.hidden = false;
    const isInProgress = ctx.stage === "IN_PROGRESS_ESTIMATE";
    const customDateRange = state.appliedEstimateParams?.has("customStartDate");
    const badgeText = ctx.customDatesEditable
      ? (customDateRange
          ? `自定义日期 · ${ctx.elapsedDays} 天`
          : (isInProgress ? `实时预估 · ${ctx.elapsedDays}/${ctx.plannedDays} 天` : `实时预估 · ${ctx.elapsedDays} 天`))
      : `含预估账期 ${(ctx.includedEstimateMonths || []).join("、")}`;

    const calRange = ctx.calibrationMonths?.length
      ? `${ctx.calibrationMonths[0]}~${ctx.calibrationMonths[ctx.calibrationMonths.length - 1]}`
      : "历史财务";
    const adCutoff = ctx.adCoveredThrough || ctx.latestAdDate;
    const adStatus = observedCoverage(adCutoff, ctx.periodStart, ctx.periodEnd, ctx.adCoveredDays, ctx.coverageDays);
    const coverageItems = [
      dataCoverage("订单", observedCoverage(ctx.latestOrderDate, ctx.periodStart, ctx.periodEnd), ctx.latestOrderDate),
      dataCoverage("广告", adStatus, adCutoff, false, ctx.adCoveredDays, ctx.coverageDays),
      dataCoverage("WFS 配送", wfsCoverage(ctx.wfsCoveredDays, ctx.coverageDays, ctx.wfsActualUsed),
        ctx.wfsCoveredThrough, true, ctx.wfsCoveredDays, ctx.coverageDays),
      dataCoverage("WFS 仓储", wfsCoverage(ctx.storageCoveredDays, ctx.coverageDays, ctx.storageActualUsed),
        ctx.storageCoveredThrough, true, ctx.storageCoveredDays, ctx.coverageDays),
    ].join("");

    const dateInputs = ctx.customDatesEditable
      ? `
        <div class="finance-est-field">
          <label for="financeEstStartDate">开始日期</label>
          <input id="financeEstStartDate" type="date" required value="${escapeHtml(ctx.periodStart || "")}">
        </div>
        <div class="finance-est-field">
          <label for="financeEstEndDate">结束日期</label>
          <input id="financeEstEndDate" type="date" required aria-describedby="financeEstimateStatus" value="${escapeHtml(ctx.periodEnd || "")}">
        </div>
      `
      : "";
    const offsiteInput = ctx.customDatesEditable
      ? `
        <div class="finance-est-field">
          <label for="financeEstOffsite">本期站外费（¥）</label>
          <input id="financeEstOffsite" type="number" step="0.01" value="${num(ctx.offsiteFeeCnyUsed).toFixed(2)}">
        </div>
      `
      : "";

    bar.innerHTML = `
      <div class="finance-est-info">
        <span class="finance-est-badge ${isInProgress ? "in-progress" : ""}">${escapeHtml(badgeText)}</span>
        <span>${ctx.customDatesEditable ? "交易日期" : "最近一期预估日期"} ${escapeHtml(ctx.periodStart)} 至 ${escapeHtml(ctx.periodEnd)} · 费率校准 ${escapeHtml(calRange)}</span>
        <ul class="finance-coverage-list" aria-label="预估数据覆盖日期">${coverageItems}</ul>
        ${ctx.refundCostRecoveryRate != null ? '<span class="finance-coverage-note">使用已维护采购成本时，退款货本回收按 ' + pct(ctx.refundCostRecoveryRate, 0) + ' 估算。</span>' : ""}
        ${adStatus !== "实测" ? '<span class="finance-coverage-note">所选预估区间的部分广告费用待同步，利润可能偏高。</span>' : ""}
      </div>
      <form class="finance-est-controls" aria-label="重算财务预估" method="get" action="/api/finance/overview">
        ${dateInputs}
        <div class="finance-est-field">
          <label for="financeEstFx">预估汇率</label>
          <input id="financeEstFx" type="number" step="0.0001" min="0.0001" value="${num(ctx.exchangeRateUsed).toFixed(4)}">
        </div>
        ${offsiteInput}
        <button class="finance-est-apply" id="financeEstApply" type="submit">重算预估</button>
        <button class="finance-est-reset" id="financeEstReset" type="button">恢复默认</button>
        <span class="finance-coverage-note" id="financeEstimateStatus" role="status"></span>
      </form>
    `;
  }

  function renderKpis() {
    const { meta, kpi } = state.data;
    const previousKpi = meta.selectedHasEstimate ? null : state.data.previousKpi;
    const grid = el("financeKpiGrid");
    if (!grid || !kpi) return;

    const estPrefix = meta.selectedAllEstimate ? "预估" : (meta.selectedHasEstimate ? "含预估的" : "");
    const profitLabel = meta.selectedAllEstimate ? "预估利润" : (meta.selectedHasEstimate ? "利润（含预估）" : "财务毛利");
    const operatingProfit = meta.estimateContext?.operatingProfitCny;
    const profitPos = num(kpi.grossProfitCny) >= 0;
    const supplyTotal = num(kpi.purchaseCostCny) + num(kpi.firstLegFreightCny);
    const offsiteVal = num(kpi.offsiteFeeCny);
    const offsiteNote = Math.abs(offsiteVal) >= 0.01
      ? ` · 站外 ${offsiteVal > 0 ? cnyShort(offsiteVal) : `冲回 ${cnyShort(Math.abs(offsiteVal))}`}`
      : "";

    grid.innerHTML = `
      <article class="finance-kpi-card featured ${profitPos ? "is-profit" : "is-loss"}">
        <div class="finance-kpi-head">
          <span class="finance-kpi-label">${profitLabel} (CNY)</span>
          ${deltaBadge(kpi.grossProfitCny, previousKpi?.grossProfitCny, "cny")}
        </div>
        <div class="finance-kpi-value ${profitPos ? "pos" : "neg"}">${cny(kpi.grossProfitCny)}</div>
        <div class="finance-kpi-sub">${estPrefix}利润率 <span class="mono">${pct(kpi.profitMargin, 2)}</span> ${deltaBadge(kpi.profitMargin, previousKpi?.profitMargin, "pp")}</div>
        ${operatingProfit != null ? `<div class="finance-kpi-sub" title="预估段利润扣除单列的库存赔偿，采购成本中已有的冲回按原口径保留">预估段经营参考 <span class="mono">${cny(operatingProfit)}</span></div>` : ""}
      </article>

      <article class="finance-kpi-card">
        <div class="finance-kpi-head">
          <span class="finance-kpi-label">${meta.selectedAllEstimate ? "实时销售收入 (USD)" : "销售收入 (USD)"}</span>
          ${deltaBadge(kpi.salesRevenueUsd, previousKpi?.salesRevenueUsd, "percent")}
        </div>
        <div class="finance-kpi-value">${usd(kpi.salesRevenueUsd)}</div>
        <div class="finance-kpi-sub">折合 <span class="mono">${cnyShort(kpi.salesRevenueCny)}</span> · 件均售价 <span class="mono">${usd(kpi.avgSellingPriceUsd)}</span></div>
      </article>

      <article class="finance-kpi-card">
        <div class="finance-kpi-head">
          <span class="finance-kpi-label">${estPrefix}平台结算净额</span>
          ${deltaBadge(kpi.netSettlementUsd, previousKpi?.netSettlementUsd, "percent")}
        </div>
        <div class="finance-kpi-value">${usd(kpi.netSettlementUsd)}</div>
        <div class="finance-kpi-sub">折合 <span class="mono">${cnyShort(kpi.netSettlementCny)}</span> · 结算率 <span class="mono">${pct(kpi.settlementRatio, 1)}</span></div>
      </article>

      <article class="finance-kpi-card">
        <div class="finance-kpi-head">
          <span class="finance-kpi-label">${estPrefix}销量</span>
          ${deltaBadge(kpi.salesQty, previousKpi?.salesQty, "percent")}
        </div>
        <div class="finance-kpi-value">${intText(kpi.salesQty)} <span class="finance-kpi-unit">件</span></div>
        <div class="finance-kpi-sub">退款率 <span class="mono">${pct(kpi.unitRefundRate, 1)}</span> · 退款 <span class="mono">${intText(kpi.refundQty)}</span> 件 · 退货 <span class="mono">${intText(kpi.returnQty)}</span> 件</div>
      </article>

      <article class="finance-kpi-card">
        <div class="finance-kpi-head">
          <span class="finance-kpi-label">平台主要费率</span>
        </div>
        <div class="finance-rate-chips">
          <div class="finance-rate-chip"><span>WFS</span><strong>${pct(kpi.wfsFeeRate, 1)}</strong></div>
          <div class="finance-rate-chip"><span>佣金</span><strong>${pct(kpi.commissionRate, 1)}</strong></div>
          <div class="finance-rate-chip"><span>广告</span><strong>${pct(kpi.adSpendRate, 1)}</strong></div>
          <div class="finance-rate-chip"><span>仓储</span><strong>${pct(kpi.storageFeeRate, 1)}</strong></div>
        </div>
        <div class="finance-kpi-sub">退款额占收入 <span class="mono">${pct(kpi.refundAmountRate, 1)}</span></div>
      </article>

      <article class="finance-kpi-card">
        <div class="finance-kpi-head">
          <span class="finance-kpi-label">${estPrefix}供应链成本 (CNY)</span>
        </div>
        <div class="finance-kpi-value">${cnyShort(supplyTotal)}</div>
        <div class="finance-kpi-sub">采购 <span class="mono">${pct(kpi.purchaseCostRate, 1)}</span> · 头程 <span class="mono">${pct(kpi.firstLegRate, 1)}</span>${offsiteNote}</div>
      </article>
    `;
  }

  function renderTrendChart() {
    const wrap = el("financeTrendChart");
    const modeEl = el("financeChartMode");
    if (!wrap || !state.data?.monthlyTrend?.length) return;

    if (modeEl) {
      modeEl.querySelectorAll("[data-chart-mode]").forEach((btn) => {
        const active = btn.dataset.chartMode === state.chartMode;
        btn.classList.toggle("active", active);
        btn.setAttribute("aria-pressed", String(active));
      });
    }

    const allPoints = state.data.monthlyTrend;
    const points = state.chartMode === "range"
      ? allPoints.filter((p) => p.inSelectedRange)
      : allPoints;
    if (!points.length) {
      wrap.innerHTML = `<div class="finance-empty">所选区间暂无月度趋势数据</div>`;
      return;
    }
    const focusedMonth = wrap.querySelector(".finance-chart-col:focus")?.dataset.month;
    const firstFocusableMonth = points.find((point) => point.month === focusedMonth)?.month
      || points.find((point) => point.inSelectedRange)?.month
      || points[0].month;

    const W = 820;
    const H = 296;
    const padL = 62;
    const padR = 50;
    const padT = 20;
    const padB = 46;
    const plotW = W - padL - padR;
    const plotH = H - padT - padB;

    const maxMoney = Math.max(
      1000,
      ...points.map((p) => Math.max(num(p.salesRevenueCny), num(p.grossProfitCny), 0))
    );
    const minMoney = Math.min(0, ...points.map((p) => Math.min(num(p.grossProfitCny), 0)));
    const topMoney = maxMoney * 1.08;
    const bottomMoney = minMoney < 0 ? minMoney * 1.18 : 0;
    const totalMoneyRange = Math.max(1, topMoney - bottomMoney);
    const yMoney = (val) => padT + ((topMoney - val) / totalMoneyRange) * plotH;
    const zeroY = yMoney(0);

    const maxMargin = Math.max(0.25, ...points.map((p) => num(p.profitMargin)));
    const minMargin = Math.min(-0.25, ...points.map((p) => num(p.profitMargin)));
    const marginTop = Math.ceil(maxMargin * 10) / 10;
    const marginBottom = Math.floor(minMargin * 10) / 10;
    const marginSpan = Math.max(0.1, marginTop - marginBottom);
    const yMargin = (val) => padT + ((marginTop - val) / marginSpan) * plotH;

    const step = plotW / points.length;
    const revBarW = Math.max(6, Math.min(20, step * 0.54));
    const profBarW = Math.max(4, Math.min(12, step * 0.32));

    const gridCount = 4;
    let gridSvg = "";
    for (let i = 0; i <= gridCount; i++) {
      const ratio = i / gridCount;
      const y = padT + ratio * plotH;
      const moneyTick = topMoney - ratio * totalMoneyRange;
      const marginTick = marginTop - ratio * marginSpan;
      gridSvg += `
        <line x1="${padL}" y1="${y.toFixed(1)}" x2="${W - padR}" y2="${y.toFixed(1)}" stroke="rgba(16,32,58,0.08)" stroke-dasharray="3 3"/>
        <text x="${padL - 8}" y="${(y + 4).toFixed(1)}" text-anchor="end" fill="#667994" font-size="10.5" font-family="var(--font-mono)">${formatAxisMoney(moneyTick)}</text>
        <text x="${W - padR + 8}" y="${(y + 4).toFixed(1)}" text-anchor="start" fill="#a9710b" font-size="10.5" font-family="var(--font-mono)">${(marginTick * 100).toFixed(0)}%</text>
      `;
    }
    gridSvg += `<line x1="${padL}" y1="${zeroY.toFixed(1)}" x2="${W - padR}" y2="${zeroY.toFixed(1)}" stroke="rgba(16,32,58,0.28)" stroke-width="1.2"/>`;

    const showEveryLabel = points.length > 18 ? 3 : points.length > 12 ? 2 : 1;
    let barsSvg = "";
    const lineCoords = [];

    points.forEach((p, idx) => {
      const cx = padL + step * idx + step / 2;
      const revVal = num(p.salesRevenueCny);
      const profVal = num(p.grossProfitCny);
      const revTop = yMoney(Math.max(0, revVal));
      const revH = Math.max(0, zeroY - revTop);

      const profTop = profVal >= 0 ? yMoney(profVal) : zeroY;
      const profH = Math.max(1.5, Math.abs(yMoney(profVal) - zeroY));
      const marginY = yMargin(num(p.profitMargin));
      lineCoords.push({ x: cx, y: marginY, p });

      const dimmed = state.chartMode === "all" && !p.inSelectedRange;
      const showLabel = idx % showEveryLabel === 0 || idx === points.length - 1 || p.estimated;
      const shortMonth = p.estimated ? `${p.month.slice(5)}估` : p.month.slice(2);

      const revFill = p.estimated
        ? "url(#finMainHatchBlue)"
        : (p.inSelectedRange ? "rgba(22,104,214,0.30)" : "rgba(22,104,214,0.18)");
      const profFill = p.estimated
        ? (profVal >= 0 ? "url(#finMainHatchGreen)" : "url(#finMainHatchRed)")
        : (profVal >= 0 ? "#0b7d5f" : "#d0343a");
      const estStroke = p.estimated
        ? `stroke="${profVal >= 0 ? "#0b7d5f" : "#d0343a"}" stroke-width="1.1" stroke-dasharray="2.5 1.5"`
        : "";
      const revStroke = p.estimated
        ? `stroke="rgba(22,104,214,0.55)" stroke-width="0.9" stroke-dasharray="2.5 1.5"`
        : "";

      const chartLabel = `${p.month} ${p.estimated ? "预估" : "财务正式"}，毛利 ${cny(p.grossProfitCny)}，选择账期`;
      barsSvg += `
        <g class="finance-chart-col ${dimmed ? "is-dimmed" : ""}" tabindex="${p.month === firstFocusableMonth ? "0" : "-1"}" role="button" aria-label="${escapeHtml(chartLabel)}" data-trend-idx="${idx}" data-month="${escapeHtml(p.month)}">
          <rect class="fin-hit" x="${(padL + step * idx).toFixed(1)}" y="${padT}" width="${step.toFixed(1)}" height="${plotH + 22}" rx="4"/>
          <rect x="${(cx - revBarW / 2).toFixed(1)}" y="${revTop.toFixed(1)}" width="${revBarW.toFixed(1)}" height="${revH.toFixed(1)}" rx="3" fill="${revFill}" ${revStroke}/>
          <rect x="${(cx - profBarW / 2).toFixed(1)}" y="${profTop.toFixed(1)}" width="${profBarW.toFixed(1)}" height="${profH.toFixed(1)}" rx="2.5" fill="${profFill}" ${estStroke}/>
          ${!p.hasStyleDetail && !p.estimated ? `<circle cx="${cx.toFixed(1)}" cy="${H - padB + 9}" r="3" fill="#ffffff" stroke="#a9710b" stroke-width="1.5"/>` : ""}
          ${showLabel ? `<text x="${cx.toFixed(1)}" y="${H - 12}" text-anchor="middle" fill="${p.estimated ? "#1655b8" : (p.inSelectedRange ? "#10203a" : "#667994")}" font-weight="${p.inSelectedRange || p.estimated ? "700" : "500"}" font-size="10.5" font-family="var(--font-mono)">${shortMonth}</text>` : ""}
        </g>
      `;
    });

    // Draw solid margin line across finalized months + dashed segment into estimated months
    const actualLine = lineCoords.filter((pt) => !pt.p.estimated);
    const estStartIdx = actualLine.length > 0 ? actualLine.length - 1 : 0;
    const estLine = lineCoords.slice(estStartIdx);

    const actualPathD = actualLine
      .map((pt, i) => `${i === 0 ? "M" : "L"}${pt.x.toFixed(1)},${pt.y.toFixed(1)}`)
      .join(" ");
    const estPathD = estLine.length > 1 && lineCoords.some((pt) => pt.p.estimated)
      ? estLine.map((pt, i) => `${i === 0 ? "M" : "L"}${pt.x.toFixed(1)},${pt.y.toFixed(1)}`).join(" ")
      : "";

    const dotsSvg = lineCoords
      .map((pt) => {
        const dimmed = state.chartMode === "all" && !pt.p.inSelectedRange;
        const stroke = pt.p.estimated ? "#1668d6" : "#a9710b";
        return `<circle cx="${pt.x.toFixed(1)}" cy="${pt.y.toFixed(1)}" r="${pt.p.inSelectedRange || pt.p.estimated ? 3.3 : 2.3}" fill="#ffffff" stroke="${stroke}" stroke-width="2" opacity="${dimmed ? 0.45 : 1}" pointer-events="none"/>`;
      })
      .join("");

    wrap.innerHTML = `
      <svg class="finance-chart-svg" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="group" aria-label="账号财务账期销售额与利润趋势，可使用左右方向键浏览月份">
        ${svgHatchDefs("finMain")}
        ${gridSvg}
        ${barsSvg}
        ${actualPathD ? `<path d="${actualPathD}" fill="none" stroke="#a9710b" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" pointer-events="none"/>` : ""}
        ${estPathD ? `<path d="${estPathD}" fill="none" stroke="#1668d6" stroke-width="2" stroke-dasharray="4 3" stroke-linejoin="round" stroke-linecap="round" pointer-events="none"/>` : ""}
        ${dotsSvg}
      </svg>
      <div class="finance-chart-tooltip" id="financeTrendTooltip" hidden></div>
    `;

    const tooltip = el("financeTrendTooltip");
    const columns = Array.from(wrap.querySelectorAll(".finance-chart-col"));
    columns.forEach((col) => {
      const idx = Number(col.dataset.trendIdx);
      const p = points[idx];
      const showTooltip = () => {
        if (!tooltip) return;
        const profCls = num(p.grossProfitCny) >= 0 ? "finance-num-pos" : "finance-num-neg";
        const stageTag = p.estimated
          ? (p.estimateStage === "IN_PROGRESS_ESTIMATE" ? "实时预估 · 进行中" : "完整账期预估")
          : "财务正式";
        const noteHtml = p.estimated
          ? `<div class="tt-note" style="color:#1655b8">${stageTag} · 点击查看该期详情并可微调参数</div>`
          : (!p.hasStyleDetail
              ? `<div class="tt-note">该月仅有店铺汇总，无单款明细</div>`
              : `<div class="tt-note" style="color:var(--steel-copy)">点击柱子可直接切换到该月</div>`);

        tooltip.innerHTML = `
          <div class="tt-head">
            <span>${escapeHtml(p.month)}${p.estimated ? ' <span class="finance-tag est">预估</span>' : ""}</span>
            <span class="tt-period">${p.settlementPeriod ? escapeHtml(p.settlementPeriod) : `汇率 ${num(p.exchangeRate).toFixed(4)}`}</span>
          </div>
          <div class="tt-row"><span>销售收入</span><strong>${usd(p.salesRevenueUsd)} (${cnyShort(p.salesRevenueCny)})</strong></div>
          <div class="tt-row"><span>平台结算</span><strong>${usd(p.netSettlementUsd)} (${cnyShort(p.netSettlementCny)})</strong></div>
          <div class="tt-row"><span>${p.estimated ? "预估毛利" : "最终毛利"}</span><strong class="${profCls}">${cny(p.grossProfitCny)}</strong></div>
          <div class="tt-row"><span>${p.estimated ? "预估利润率" : "利润率"}</span><strong class="${profCls}">${pct(p.profitMargin, 2)}</strong></div>
          <div class="tt-row"><span>销量 / 退款率</span><strong>${intText(p.salesQty)} 件 · ${pct(p.unitRefundRate, 1)}</strong></div>
          ${noteHtml}
        `;
        tooltip.style.left = "";
        tooltip.style.top = "";
        tooltip.style.right = "";
        tooltip.hidden = false;
      };
      const selectMonth = () => {
        state.activePreset = p.estimated ? `est:${p.month}` : (p.month === state.data?.meta?.latestFinalMonth ? "latest" : "custom");
        clearEstimateOverrides();
        loadRange(p.month, p.month);
      };
      col.addEventListener("mouseenter", showTooltip);
      col.addEventListener("focus", () => {
        wrap.querySelector('.finance-chart-col[tabindex="0"]')?.setAttribute("tabindex", "-1");
        col.setAttribute("tabindex", "0");
        showTooltip();
      });
      col.addEventListener("mousemove", (event) => {
        if (!tooltip || tooltip.hidden) return;
        const rect = wrap.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const left = x + 16 + tooltip.offsetWidth > rect.width ? Math.max(6, x - tooltip.offsetWidth - 14) : x + 14;
        const top = Math.max(4, Math.min(rect.height - tooltip.offsetHeight - 4, y - 24));
        tooltip.style.right = "auto";
        tooltip.style.left = `${left}px`;
        tooltip.style.top = `${top}px`;
      });
      col.addEventListener("mouseleave", () => {
        if (tooltip && document.activeElement !== col) tooltip.hidden = true;
      });
      col.addEventListener("blur", () => {
        if (tooltip) tooltip.hidden = true;
      });
      col.addEventListener("click", selectMonth);
      col.addEventListener("keydown", (event) => {
        switch (event.key) {
          case "Enter":
          case " ":
            event.preventDefault();
            if (!event.repeat) selectMonth();
            break;
          case "ArrowLeft":
          case "ArrowRight":
            event.preventDefault();
            columns[idx + (event.key === "ArrowLeft" ? -1 : 1)]?.focus();
            break;
        }
      });
    });
    if (focusedMonth) columns.find((column) => column.dataset.month === focusedMonth)?.focus({ preventScroll: true });
  }

  function formatAxisMoney(val) {
    const abs = Math.abs(val);
    const sign = val < 0 ? "-" : "";
    if (abs >= 10000) return `${sign}¥${(abs / 10000).toFixed(abs >= 100000 ? 0 : 1)}万`;
    if (abs >= 1000) return `${sign}¥${(abs / 1000).toFixed(0)}k`;
    return `${sign}¥${Math.round(abs)}`;
  }

  function renderBreakdown() {
    const body = el("financeBreakdownBody");
    const items = state.data?.costBreakdown || [];
    const showSource = Boolean(state.data?.meta?.selectedHasEstimate);
    if (!body) return;
    if (!items.length) {
      body.innerHTML = `<div class="finance-empty">暂无损益结构数据</div>`;
      return;
    }

    const rowsHtml = items.map((item) => {
      const share = num(item.shareOfRevenue);
      const barPct = Math.min(100, Math.abs(share) * 100);
      const isProfit = item.group === "PROFIT";
      const isRevenue = item.group === "REVENUE";
      const isSubtotal = item.group === "SUBTOTAL";
      const isCost = item.group === "CNY_COST";
      const rowCls = isProfit
        ? `is-profit ${num(item.amountCny) >= 0 ? "pos" : "neg"}`
        : isRevenue ? "is-revenue"
        : isSubtotal ? "is-subtotal" : "";

      const barCls = isRevenue ? "rev"
        : isSubtotal ? "subtotal"
        : isProfit ? (num(item.amountCny) >= 0 ? "pos" : "neg")
        : isCost ? (num(item.amountCny) <= 0 ? "cost" : "pos")
        : (num(item.amountCny) <= 0 ? "deduct" : "pos");

      const cnyCls = isProfit
        ? (num(item.amountCny) >= 0 ? "pos" : "neg")
        : (num(item.amountCny) > 0 && !isRevenue && !isSubtotal ? "pos" : "");

      return `
        <div class="finance-bd-row ${rowCls}">
          <span class="finance-bd-label" title="${escapeHtml(item.label)}">${escapeHtml(item.label)}${sourcePillHtml(item.sourceTag, showSource)}</span>
          <div class="finance-bd-bar-track">
            <div class="finance-bd-bar ${barCls}" style="width:${barPct.toFixed(1)}%"></div>
          </div>
          <span class="finance-bd-num">${pct(share, 1)}</span>
          <span class="finance-bd-num ${item.amountUsd == null ? "muted" : ""}">${item.amountUsd == null ? "—" : usd(item.amountUsd)}</span>
          <span class="finance-bd-num ${cnyCls}">${cnyShort(item.amountCny)}</span>
        </div>
      `;
    }).join("");

    body.innerHTML = `
      <div class="finance-bd-head">
        <span>科目</span>
        <span>占收入比例</span>
        <span>占比</span>
        <span>美元</span>
        <span>人民币</span>
      </div>
      <div class="finance-breakdown-list">${rowsHtml}</div>
    `;
  }

  function renderFilterOptions() {
    const opts = state.data?.filterOptions || { categories: [], seasons: [], statuses: [] };
    const fill = (selectId, placeholder, list, currentVal) => {
      const sel = el(selectId);
      if (!sel) return;
      sel.innerHTML = `<option value="">${placeholder}</option>` + list
        .map((v) => `<option value="${escapeHtml(v)}" ${v === currentVal ? "selected" : ""}>${escapeHtml(v)}</option>`)
        .join("");
      if (currentVal && !list.includes(currentVal)) {
        sel.value = "";
        return "";
      }
      return currentVal;
    };
    state.categoryFilter = fill("financeCategoryFilter", "全部类目", opts.categories || [], state.categoryFilter);
    state.seasonFilter = fill("financeSeasonFilter", "全部季节", opts.seasons || [], state.seasonFilter);
    state.statusFilter = fill("financeStatusFilter", "全部状态", opts.statuses || [], state.statusFilter);
  }

  function isClearanceStatus(status) {
    return String(status || "").includes("清算");
  }

  function isClearanceStyle(s) {
    return isClearanceStatus(s?.listingStatus);
  }

  function filteredStyles() {
    const all = state.data?.styles || [];
    const q = state.search.toLowerCase();
    const list = all.filter((s) => {
      if (state.hideClearance && isClearanceStyle(s)) return false;
      if (state.profitFilter === "profit" && !(num(s.profitCny) > 0)) return false;
      if (state.profitFilter === "loss" && !(num(s.profitCny) < 0)) return false;
      if (state.categoryFilter && s.category !== state.categoryFilter) return false;
      if (state.seasonFilter && s.season !== state.seasonFilter) return false;
      if (state.statusFilter && s.listingStatus !== state.statusFilter) return false;
      if (q) {
        const hay = `${s.parentSku} ${s.productName || ""} ${s.category || ""} ${s.season || ""}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });

    const dir = state.sortDir === "asc" ? 1 : -1;
    list.sort((a, b) => {
      if (state.sortKey === "parentSku") {
        return dir * String(a.parentSku).localeCompare(String(b.parentSku), "zh-CN", { numeric: true });
      }
      if (state.sortKey === "supplyCost") {
        const av = num(a.purchaseCostCny) + num(a.firstLegCostCny);
        const bv = num(b.purchaseCostCny) + num(b.firstLegCostCny);
        return dir * (av - bv);
      }
      return dir * (num(a[state.sortKey]) - num(b[state.sortKey]));
    });
    return list;
  }

  function renderStyleTable() {
    if (!state.data?.meta?.availableMonths?.length) return;
    const { meta, styleSummary } = state.data;
    const noticeEl = el("financeMissingNotice");
    const missing = meta.missingStyleMonthsInRange || [];
    if (noticeEl) {
      if (missing.length > 0) {
        noticeEl.hidden = false;
        const text = meta.selectedMonthCount === 1
          ? `${missing[0]} 仅有店铺月度汇总数据，暂无单款明细。`
          : `所选区间中 ${missing.join("、")} 仅有店铺汇总、无单款明细；上方账号 KPI 包含全部 ${meta.selectedMonthCount} 个月，下方款号汇总仅统计有单款明细的 ${styleSummary.coveredStyleMonths} 个月。`;
        noticeEl.innerHTML = `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v5m0 3.5h.01"/></svg>
          <span>${escapeHtml(text)}</span>
        `;
      } else {
        noticeEl.hidden = true;
      }
    }

    el("financeProfitFilter")?.querySelectorAll("[data-profit-filter]").forEach((btn) => {
      const active = btn.dataset.profitFilter === state.profitFilter;
      btn.classList.toggle("active", active);
      btn.setAttribute("aria-pressed", String(active));
    });

    const allStyles = state.data?.styles || [];
    const clearanceCount = allStyles.filter(isClearanceStyle).length;
    const toggleClearanceBtn = el("financeToggleClearance");
    if (toggleClearanceBtn) {
      const eyeIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>`;
      const eyeOffIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9.9 4.2A10.7 10.7 0 0 1 12 4c6.4 0 10 8 10 8a18.5 18.5 0 0 1-3.2 4.4M6.6 6.6A18.2 18.2 0 0 0 2 12s3.6 8 10 8a10.4 10.4 0 0 0 5.4-1.5"/><path d="M14.1 14.1a3 3 0 0 1-4.2-4.2"/><path d="M2 2l20 20"/></svg>`;
      toggleClearanceBtn.classList.toggle("active", state.hideClearance);
      toggleClearanceBtn.setAttribute("aria-pressed", String(state.hideClearance));
      toggleClearanceBtn.disabled = !state.hideClearance && clearanceCount === 0;
      if (state.hideClearance) {
        toggleClearanceBtn.innerHTML = `${eyeIcon}<span>${clearanceCount > 0 ? `显示清算款 (已隐藏 ${clearanceCount})` : "显示清算款 (本期无)"}</span>`;
      } else {
        toggleClearanceBtn.innerHTML = `${eyeOffIcon}<span>${clearanceCount > 0 ? `隐藏清算款 (${clearanceCount})` : "无清算款"}</span>`;
      }
    }

    el("financeStyleHead")?.querySelectorAll("th[data-sort]").forEach((th) => {
      const isSorted = th.dataset.sort === state.sortKey;
      th.classList.toggle("sorted", isSorted);
      if (isSorted) th.setAttribute("aria-sort", state.sortDir === "desc" ? "descending" : "ascending");
      else th.removeAttribute("aria-sort");
      const arrow = th.querySelector(".finance-sort-arrow");
      if (arrow) arrow.textContent = isSorted ? (state.sortDir === "desc" ? "▼" : "▲") : "";
    });

    const rows = filteredStyles();
    const badgesEl = el("financeStyleBadges");
    if (badgesEl) {
      const profCount = rows.filter((r) => num(r.profitCny) > 0).length;
      const lossCount = rows.filter((r) => num(r.profitCny) < 0).length;
      badgesEl.innerHTML = `
        <span class="finance-stat-pill">款号 <strong>${rows.length}</strong></span>
        <span class="finance-stat-pill pos">盈利 <strong>${profCount}</strong></span>
        <span class="finance-stat-pill neg">亏损 <strong>${lossCount}</strong></span>
        ${state.hideClearance && clearanceCount > 0 ? `<span class="finance-stat-pill clearance">已隐藏清算款 <strong>${clearanceCount}</strong></span>` : ""}
        ${meta.selectedAllEstimate ? `<span class="finance-stat-pill" style="background:rgba(22,104,214,0.1);color:#1655b8">当前为实时预估</span>` : ""}
      `;
    }

    const tbody = el("financeStyleBody");
    const tfoot = el("financeStyleFoot");
    if (!tbody || !tfoot) return;

    if (!rows.length) {
      const emptyMsg = meta.selectedMonthCount === 1 && missing.length === 1
        ? `${missing[0]} 仅有店铺汇总，暂无单款明细`
        : (state.hideClearance && clearanceCount > 0
            ? "当前结果已被清算款筛选隐藏，点击「显示清算款」可恢复查看"
            : "当前筛选条件下没有匹配的款号记录");
      tbody.innerHTML = `<tr><td colspan="13"><div class="finance-empty"><strong>${escapeHtml(emptyMsg)}</strong></div></td></tr>`;
      tfoot.innerHTML = "";
      return;
    }

    const multiMonth = meta.selectedMonthCount > 1;
    let sumQty = 0, sumRefundQty = 0, sumRevUsd = 0, sumRevCny = 0;
    let sumWfsUsd = 0, sumAdUsd = 0, sumNetCny = 0, sumPurCny = 0, sumFlCny = 0, sumOffCny = 0, sumProfCny = 0;

    tbody.innerHTML = rows.map((s) => {
      sumQty += num(s.salesQty);
      sumRefundQty += num(s.refundQty);
      sumRevUsd += num(s.salesRevenueUsd);
      sumRevCny += num(s.salesRevenueCny);
      sumWfsUsd += num(s.wfsFeeUsd);
      sumAdUsd += num(s.adSpendUsd);
      sumNetCny += num(s.netSettlementCny);
      sumPurCny += num(s.purchaseCostCny);
      sumFlCny += num(s.firstLegCostCny);
      sumOffCny += num(s.offsiteFeeCny);
      sumProfCny += num(s.profitCny);

      const img = safeImageUrl(s.thumbUrl);
      const thumbHtml = img
        ? `<img class="finance-thumb" src="${escapeHtml(img)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer">`
        : `<span class="finance-thumb">${escapeHtml(s.parentSku.slice(-4))}</span>`;

      const statusCls = isClearanceStyle(s) ? "clearance" : s.listingStatus === "现售" ? "active-sale" : "";
      const fallbackTag = s.costBasis === "PROFIT_ASSUMPTION" || s.costBasis === "SKU_ASSUMPTION"
        ? `<span class="finance-tag fallback" title="采购和头程采用工作台毛利估算设置">工作台成本</span>`
        : (s.costBasis === "STORE_FALLBACK"
            ? `<span class="finance-tag fallback" title="缺少该款历史与假设成本，按店铺均值推算">均值成本</span>`
            : "");
      const tags = [
        s.listingStatus ? `<span class="finance-tag ${statusCls}">${escapeHtml(s.listingStatus)}</span>` : "",
        s.category ? `<span class="finance-tag">${escapeHtml(s.category)}</span>` : "",
        s.season ? `<span class="finance-tag">${escapeHtml(s.season)}</span>` : "",
        multiMonth ? `<span class="finance-tag">${s.monthsActive}月</span>` : "",
        fallbackTag,
      ].filter(Boolean).join("");

      const profVal = num(s.profitCny);
      const profCls = profVal > 0 ? "finance-num-pos" : profVal < 0 ? "finance-num-neg" : "";
      const marginVal = num(s.profitMargin);
      const marginBarW = Math.min(100, Math.abs(marginVal) * 200);
      const supplyCost = num(s.purchaseCostCny) + num(s.firstLegCostCny);
      const supplyRate = num(s.salesRevenueCny) > 0 ? supplyCost / num(s.salesRevenueCny) : 0;
      const refundRateCls = num(s.unitRefundRate) >= 0.15 ? "finance-num-warn" : "";
      const adRateCls = num(s.adSpendRate) >= 0.15 ? "finance-num-warn" : "";

      return `
        <tr data-style-sku="${escapeHtml(s.parentSku)}">
          <td>
            <div class="finance-sku-cell">
              ${thumbHtml}
              <div class="finance-sku-info">
                <div class="finance-sku-code">${escapeHtml(s.parentSku)}</div>
                ${s.productName ? `<div class="finance-sku-title" title="${escapeHtml(s.productName)}">${escapeHtml(s.productName)}</div>` : ""}
                ${tags ? `<div class="finance-sku-tags">${tags}</div>` : ""}
              </div>
            </div>
          </td>
          <td class="num">${intText(s.salesQty)}</td>
          <td class="num">${s.salesQty > 0 ? usd(s.avgSellingPriceUsd) : "—"}</td>
          <td class="num">${usd(s.salesRevenueUsd)}<span class="finance-sub-num">${cnyShort(s.salesRevenueCny)}</span></td>
          <td class="num ${refundRateCls}">${pct(s.unitRefundRate, 1)}<span class="finance-sub-num">${intText(s.refundQty)}件</span></td>
          <td class="num">${pct(s.wfsFeeRate, 1)}<span class="finance-sub-num">${usd(Math.abs(num(s.wfsFeeUsd)))}</span></td>
          <td class="num ${adRateCls}">${pct(s.adSpendRate, 1)}<span class="finance-sub-num">${usd(Math.abs(num(s.adSpendUsd)))}</span></td>
          <td class="num">${cnyShort(s.netSettlementCny)}</td>
          <td class="num">${cnyShort(supplyCost)}<span class="finance-sub-num">${pct(supplyRate, 1)}</span></td>
          <td class="num">${Math.abs(num(s.offsiteFeeCny)) >= 0.01 ? cnyShort(s.offsiteFeeCny) : "—"}</td>
          <td class="num ${profCls}">${cny(s.profitCny)}</td>
          <td class="num">
            <div class="finance-margin-cell">
              <span class="${profCls}">${pct(s.profitMargin, 1)}</span>
              <div class="finance-margin-track"><div class="finance-margin-fill ${marginVal >= 0 ? "pos" : "neg"}" style="width:${marginBarW.toFixed(1)}%"></div></div>
            </div>
          </td>
          <td><button class="finance-detail-btn" type="button" data-style-sku="${escapeHtml(s.parentSku)}" aria-label="查看 ${escapeHtml(s.parentSku)} 月度趋势">月度趋势</button></td>
        </tr>
      `;
    }).join("");

    const totalAsp = sumQty > 0 ? sumRevUsd / sumQty : 0;
    const totalRefundRate = sumQty > 0 ? sumRefundQty / sumQty : 0;
    const totalWfsRate = sumRevUsd > 0 ? Math.abs(sumWfsUsd) / sumRevUsd : 0;
    const totalAdRate = sumRevUsd > 0 ? Math.abs(sumAdUsd) / sumRevUsd : 0;
    const totalSupply = sumPurCny + sumFlCny;
    const totalSupplyRate = sumRevCny > 0 ? totalSupply / sumRevCny : 0;
    const totalMargin = sumRevCny > 0 ? sumProfCny / sumRevCny : 0;
    const totalProfCls = sumProfCny >= 0 ? "finance-num-pos" : "finance-num-neg";

    tfoot.innerHTML = `
      <tr>
        <td>筛选合计（${rows.length} 款）</td>
        <td class="num">${intText(sumQty)}</td>
        <td class="num">${sumQty > 0 ? usd(totalAsp) : "—"}</td>
        <td class="num">${usd(sumRevUsd)}<span class="finance-sub-num">${cnyShort(sumRevCny)}</span></td>
        <td class="num">${pct(totalRefundRate, 1)}<span class="finance-sub-num">${intText(sumRefundQty)}件</span></td>
        <td class="num">${pct(totalWfsRate, 1)}</td>
        <td class="num">${pct(totalAdRate, 1)}</td>
        <td class="num">${cnyShort(sumNetCny)}</td>
        <td class="num">${cnyShort(totalSupply)}<span class="finance-sub-num">${pct(totalSupplyRate, 1)}</span></td>
        <td class="num">${Math.abs(sumOffCny) >= 0.01 ? cnyShort(sumOffCny) : "—"}</td>
        <td class="num ${totalProfCls}">${cny(sumProfCny)}</td>
        <td class="num ${totalProfCls}">${pct(totalMargin, 1)}</td>
        <td></td>
      </tr>
    `;
  }

  async function openStyleDrawer(parentSku) {
    const drawer = el("financeStyleDrawer");
    const titleEl = el("financeDrawerTitle");
    const metaEl = el("financeDrawerMeta");
    const bodyEl = el("financeDrawerBody");
    if (!drawer || !bodyEl) return;

    state.drawerRequest?.abort();
    const request = new AbortController();
    state.drawerRequest = request;
    const overview = state.data;
    const params = new URLSearchParams(state.appliedEstimateParams || estimateRequestParams());
    if (overview?.meta?.estimateContext?.customDatesEditable && params.has("customStartDate")) {
      params.set("targetEstimateMonth", overview.meta.estimateContext.month);
    }
    titleEl.textContent = `${parentSku} · 跨月利润详情`;
    metaEl.textContent = "正在加载款号账期明细…";
    bodyEl.innerHTML = `<div class="finance-empty">加载中…</div>`;
    if (!drawer.open) drawer.showModal();

    try {
      const detail = await fetchJson(`/styles/${encodeURIComponent(parentSku)}?${params}`, { signal: request.signal });
      if (!request.signal.aborted) renderStyleDrawer(detail, overview);
    } catch (error) {
      if (request.signal.aborted) return;
      metaEl.textContent = "加载失败";
      bodyEl.innerHTML = `<div class="finance-empty"><strong>无法加载款号明细</strong>${escapeHtml(error.message)}</div>`;
    }
  }

  function renderStyleDrawer(detail, overview) {
    const titleEl = el("financeDrawerTitle");
    const metaEl = el("financeDrawerMeta");
    const bodyEl = el("financeDrawerBody");
    const lt = detail.lifetime;

    titleEl.textContent = `${detail.parentSku} · 跨月利润轨迹`;
    const estNote = lt.estimatedMonthsCount > 0
      ? (lt.finalizedOnly
          ? `（上方累计为财务正式 ${lt.monthsActive} 个月；下方已追加 ${lt.estimatedMonthsCount} 期实时预估）`
          : `（该款为新品，当前仅含 ${lt.estimatedMonthsCount} 期实时预估）`)
      : `（共 ${lt.monthsActive} 个财务月）`;
    metaEl.textContent = `${lt.firstMonth} 至 ${lt.lastMonth} ${estNote}`;

    const img = safeImageUrl(detail.thumbUrl);
    const thumbHtml = img
      ? `<img class="finance-drawer-thumb" src="${escapeHtml(img)}" alt="" referrerpolicy="no-referrer">`
      : `<span class="finance-drawer-thumb">${escapeHtml(detail.parentSku.slice(-4))}</span>`;

    const metaTags = [detail.listingStatus, detail.category, detail.season, detail.ownerName]
      .filter(Boolean)
      .map((t) => `<span class="finance-tag">${escapeHtml(t)}</span>`)
      .join("");

    const profCls = num(lt.profitCny) >= 0 ? "pos" : "neg";
    const selected = overview?.styles?.find((style) => style.parentSku === detail.parentSku);
    const selection = overview?.meta;
    const selectionLabel = selection?.selectedSettlementPeriod
      ? `${selection.selectedStartMonth}（${selection.selectedSettlementPeriod}）`
      : `${selection?.selectedStartMonth || ""} 至 ${selection?.selectedEndMonth || ""}`;

    const monthsDesc = detail.months.slice().reverse();
    const tableRows = monthsDesc.map((m) => {
      const mProfCls = num(m.profitCny) > 0 ? "finance-num-pos" : num(m.profitCny) < 0 ? "finance-num-neg" : "";
      const estPill = m.estimated
        ? ` <span class="finance-tag est">${m.estimateStage === "IN_PROGRESS_ESTIMATE" ? "进行中" : "预估"}</span>`
        : "";
      const refundSource = m.estimated ? basisText(m.refundBasis) : "";
      const commissionSource = m.estimated ? basisText(m.commissionBasis) : "";
      const costSource = m.estimated ? basisText(m.costBasis) : "";
      return `
        <tr ${m.estimated ? 'style="background:rgba(22,104,214,0.035)"' : ""}>
          <td class="mono" style="white-space:nowrap"><strong>${escapeHtml(m.month)}</strong>${estPill}${m.settlementPeriod ? `<span class="finance-sub-num">${escapeHtml(m.settlementPeriod)}</span>` : ""}</td>
          <td class="num">${intText(m.salesQty)}</td>
          <td class="num">${m.salesQty > 0 ? usd(m.avgSellingPriceUsd) : "—"}</td>
          <td class="num">${usd(m.salesRevenueUsd)}</td>
          <td class="num">${pct(m.unitRefundRate, 1)}<span class="finance-sub-num">${intText(m.refundQty)}件 · ${usd(Math.abs(num(m.refundUsd)))}</span>${refundSource ? `<span class="finance-sub-num">${refundSource}</span>` : ""}</td>
          <td class="num">${pct(m.wfsFeeRate, 1)}</td>
          <td class="num">${pct(m.commissionRate, 1)}${commissionSource ? `<span class="finance-sub-num">${commissionSource}</span>` : ""}</td>
          <td class="num">${pct(m.adSpendRate, 1)}</td>
          <td class="num">${usd(Math.abs(num(m.storageFeeUsd)))}</td>
          <td class="num">${cnyShort(m.netSettlementCny)}</td>
          <td class="num">${cnyShort(m.purchaseCostCny)}${costSource ? `<span class="finance-sub-num">${costSource}</span>` : ""}</td>
          <td class="num">${cnyShort(m.firstLegCostCny)}</td>
          <td class="num">${Math.abs(num(m.offsiteFeeCny)) >= 0.01 ? cnyShort(m.offsiteFeeCny) : "—"}</td>
          <td class="num ${mProfCls}">${cny(m.profitCny)}${m.operatingProfitCny != null
            ? `<span class="finance-sub-num" title="剔除一次性库存赔偿">经营参考 ${cny(m.operatingProfitCny)}</span>` : ""}</td>
          <td class="num ${mProfCls}">${pct(m.profitMargin, 1)}</td>
        </tr>
      `;
    }).join("");

    bodyEl.innerHTML = `
      <div class="finance-drawer-hero">
        ${thumbHtml}
        <div style="min-width:0">
          <div style="font:700 15px/1.3 var(--font-mono);color:var(--frost-ink)">${escapeHtml(detail.parentSku)}</div>
          ${detail.productName ? `<div style="margin:3px 0 6px;color:var(--steel-copy);font-size:12.5px">${escapeHtml(detail.productName)}</div>` : ""}
          <div class="finance-sku-tags">${metaTags}</div>
          ${openInventory && detail.parentSku !== "未归属" ? '<button class="finance-detail-btn" type="button" data-finance-inventory="' + escapeHtml(detail.parentSku) + '">查看 SKU 库存</button>' : ""}
        </div>
      </div>

      ${selected ? `
        <div class="finance-drawer-period">
          <strong>${selection.selectedHasEstimate ? "当前区间利润（含预估）" : "当前区间财务毛利"} ${cny(selected.profitCny)}</strong>
          <span>${escapeHtml(selectionLabel)} · ${intText(selected.salesQty)} 件 · ${usd(selected.salesRevenueUsd)}</span>
        </div>
      ` : ""}

      <div class="finance-drawer-kpis">
        <div class="finance-drawer-kpi"><span>${lt.finalizedOnly ? "正式累计销量" : "预估累计销量"}</span><strong>${intText(lt.salesQty)} 件</strong></div>
        <div class="finance-drawer-kpi"><span>${lt.finalizedOnly ? "正式累计销售额" : "预估销售额"}</span><strong>${usd(lt.salesRevenueUsd)}</strong></div>
        <div class="finance-drawer-kpi"><span>${lt.finalizedOnly ? "正式累计结算" : "预估结算"}</span><strong>${cnyShort(lt.netSettlementCny)}</strong></div>
        <div class="finance-drawer-kpi"><span>${lt.finalizedOnly ? "正式累计毛利" : "预估毛利"}</span><strong class="${profCls}">${cny(lt.profitCny)}</strong></div>
        <div class="finance-drawer-kpi"><span>${lt.finalizedOnly ? "正式综合利润率" : "预估利润率"}</span><strong class="${profCls}">${pct(lt.profitMargin, 2)}</strong></div>
      </div>

      <div class="finance-drawer-chart-panel">
        <div class="finance-panel-head" style="margin-bottom:8px">
          <div>
            <h3 class="finance-panel-title" style="font-size:13.5px">月度毛利（¥）与利润率（%）走势</h3>
            <p class="finance-panel-sub">${lt.estimatedMonthsCount > 0 ? "绿色为盈利、红色为亏损，斜纹柱为实时预估周期，折线为利润率" : "绿色为盈利、红色为亏损，折线为利润率（仅财务正式月份）"}</p>
          </div>
        </div>
        <div class="finance-chart-wrap" id="financeDrawerChart" style="min-height:220px"></div>
      </div>

      <div class="finance-table-scroll">
        <table class="finance-table">
          <thead>
            <tr>
              <th>财务账期</th>
              <th class="num">销量</th>
              <th class="num">件均售价</th>
              <th class="num">销售额($)</th>
              <th class="num">退款率</th>
              <th class="num">WFS%</th>
              <th class="num">佣金%</th>
              <th class="num">广告%</th>
              <th class="num">仓储($)</th>
              <th class="num">结算(¥)</th>
              <th class="num">采购(¥)</th>
              <th class="num">头程(¥)</th>
              <th class="num">站外(¥)</th>
              <th class="num">毛利 / 预估利润(¥)</th>
              <th class="num">利润率</th>
            </tr>
          </thead>
          <tbody>${tableRows}</tbody>
          <tfoot>
            <tr>
              <td>${lt.finalizedOnly ? `正式累计（${lt.monthsActive}月）` : `预估累计（${lt.monthsActive}月）`}</td>
              <td class="num">${intText(lt.salesQty)}</td>
              <td class="num">${lt.salesQty > 0 ? usd(lt.avgSellingPriceUsd) : "—"}</td>
              <td class="num">${usd(lt.salesRevenueUsd)}</td>
              <td class="num">${pct(lt.unitRefundRate, 1)}</td>
              <td class="num">${pct(lt.wfsFeeRate, 1)}</td>
              <td class="num">${pct(lt.commissionRate, 1)}</td>
              <td class="num">${pct(lt.adSpendRate, 1)}</td>
              <td class="num">${usd(Math.abs(num(lt.storageFeeUsd)))}</td>
              <td class="num">${cnyShort(lt.netSettlementCny)}</td>
              <td class="num">${cnyShort(lt.purchaseCostCny)}</td>
              <td class="num">${cnyShort(lt.firstLegCostCny)}</td>
              <td class="num">${Math.abs(num(lt.offsiteFeeCny)) >= 0.01 ? cnyShort(lt.offsiteFeeCny) : "—"}</td>
              <td class="num ${num(lt.profitCny) >= 0 ? "finance-num-pos" : "finance-num-neg"}">${cny(lt.profitCny)}</td>
              <td class="num ${num(lt.profitCny) >= 0 ? "finance-num-pos" : "finance-num-neg"}">${pct(lt.profitMargin, 1)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    `;

    renderDrawerChart(detail.months);
  }

  function renderDrawerChart(months) {
    const wrap = el("financeDrawerChart");
    if (!wrap || !months?.length) return;

    const W = 860;
    const H = 215;
    const padL = 56;
    const padR = 46;
    const padT = 16;
    const padB = 34;
    const plotW = W - padL - padR;
    const plotH = H - padT - padB;

    const maxProf = Math.max(500, ...months.map((m) => Math.max(num(m.profitCny), 0)));
    const minProf = Math.min(0, ...months.map((m) => Math.min(num(m.profitCny), 0)));
    const topVal = maxProf * 1.15;
    const botVal = minProf < 0 ? minProf * 1.18 : 0;
    const span = Math.max(1, topVal - botVal);
    const yProf = (v) => padT + ((topVal - v) / span) * plotH;
    const zeroY = yProf(0);

    const maxM = Math.max(0.25, ...months.map((m) => num(m.profitMargin)));
    const minM = Math.min(-0.25, ...months.map((m) => num(m.profitMargin)));
    const mTop = Math.ceil(maxM * 10) / 10;
    const mBot = Math.floor(minM * 10) / 10;
    const mSpan = Math.max(0.1, mTop - mBot);
    const yMargin = (v) => padT + ((mTop - v) / mSpan) * plotH;

    const step = plotW / months.length;
    const barW = Math.max(8, Math.min(26, step * 0.48));
    const showEvery = months.length > 16 ? 2 : 1;

    let grid = "";
    for (let i = 0; i <= 3; i++) {
      const r = i / 3;
      const y = padT + r * plotH;
      grid += `
        <line x1="${padL}" y1="${y.toFixed(1)}" x2="${W - padR}" y2="${y.toFixed(1)}" stroke="rgba(16,32,58,0.08)" stroke-dasharray="3 3"/>
        <text x="${padL - 7}" y="${(y + 4).toFixed(1)}" text-anchor="end" fill="#667994" font-size="10" font-family="var(--font-mono)">${formatAxisMoney(topVal - r * span)}</text>
        <text x="${W - padR + 7}" y="${(y + 4).toFixed(1)}" text-anchor="start" fill="#a9710b" font-size="10" font-family="var(--font-mono)">${((mTop - r * mSpan) * 100).toFixed(0)}%</text>
      `;
    }
    grid += `<line x1="${padL}" y1="${zeroY.toFixed(1)}" x2="${W - padR}" y2="${zeroY.toFixed(1)}" stroke="rgba(16,32,58,0.28)"/>`;

    let bars = "";
    const pts = [];
    months.forEach((m, idx) => {
      const cx = padL + step * idx + step / 2;
      const pv = num(m.profitCny);
      const top = pv >= 0 ? yProf(pv) : zeroY;
      const bh = Math.max(2, Math.abs(yProf(pv) - zeroY));
      const my = yMargin(num(m.profitMargin));
      pts.push({ x: cx, y: my, est: m.estimated });
      const showLbl = idx % showEvery === 0 || idx === months.length - 1 || m.estimated;
      const fill = m.estimated
        ? (pv >= 0 ? "url(#finDrawHatchGreen)" : "url(#finDrawHatchRed)")
        : (pv >= 0 ? "#0b7d5f" : "#d0343a");
      const stroke = m.estimated
        ? `stroke="${pv >= 0 ? "#0b7d5f" : "#d0343a"}" stroke-width="1.1" stroke-dasharray="2.5 1.5"`
        : "";
      bars += `
        <g class="finance-chart-col" data-d-idx="${idx}">
          <rect class="fin-hit" x="${(padL + step * idx).toFixed(1)}" y="${padT}" width="${step.toFixed(1)}" height="${plotH + 14}" rx="3"/>
          <rect x="${(cx - barW / 2).toFixed(1)}" y="${top.toFixed(1)}" width="${barW.toFixed(1)}" height="${bh.toFixed(1)}" rx="3" fill="${fill}" ${stroke}/>
          ${showLbl ? `<text x="${cx.toFixed(1)}" y="${H - 8}" text-anchor="middle" fill="${m.estimated ? "#1655b8" : "#4f6180"}" font-weight="${m.estimated ? "700" : "500"}" font-size="10" font-family="var(--font-mono)">${m.estimated ? `${m.month.slice(5)}估` : m.month.slice(2)}</text>` : ""}
        </g>
      `;
    });

    const d = pts.map((p, i) => `${i === 0 ? "M" : "L"}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ");
    const dots = pts.map((p) => `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="2.8" fill="#fff" stroke="${p.est ? "#1668d6" : "#a9710b"}" stroke-width="1.8" pointer-events="none"/>`).join("");

    wrap.innerHTML = `
      <svg class="finance-drawer-chart-svg" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
        ${svgHatchDefs("finDraw")}
        ${grid}${bars}
        <path d="${d}" fill="none" stroke="#a9710b" stroke-width="2" stroke-linejoin="round" pointer-events="none"/>
        ${dots}
      </svg>
      <div class="finance-chart-tooltip" id="financeDrawerTooltip" hidden></div>
    `;

    const tip = el("financeDrawerTooltip");
    wrap.querySelectorAll(".finance-chart-col").forEach((col) => {
      const m = months[Number(col.dataset.dIdx)];
      col.addEventListener("mouseenter", () => {
        if (!tip) return;
        const cls = num(m.profitCny) >= 0 ? "finance-num-pos" : "finance-num-neg";
        tip.innerHTML = `
          <div class="tt-head"><span>${escapeHtml(m.month)}${m.estimated ? ' <span class="finance-tag est">预估</span>' : ""}</span><span class="tt-period">${escapeHtml(m.settlementPeriod || "")}</span></div>
          <div class="tt-row"><span>销售额 / 销量</span><strong>${usd(m.salesRevenueUsd)} · ${intText(m.salesQty)}件</strong></div>
          <div class="tt-row"><span>${m.estimated ? "预估毛利" : "毛利"}</span><strong class="${cls}">${cny(m.profitCny)}</strong></div>
          <div class="tt-row"><span>${m.estimated ? "预估利润率" : "利润率"}</span><strong class="${cls}">${pct(m.profitMargin, 2)}</strong></div>
          <div class="tt-row"><span>退款 / 广告</span><strong>${pct(m.unitRefundRate, 1)} / ${pct(m.adSpendRate, 1)}</strong></div>
        `;
        tip.hidden = false;
      });
      col.addEventListener("mousemove", (event) => {
        if (!tip || tip.hidden) return;
        const rect = wrap.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const left = x + 14 + tip.offsetWidth > rect.width ? Math.max(4, x - tip.offsetWidth - 12) : x + 12;
        tip.style.left = `${left}px`;
        tip.style.top = `${Math.max(4, y - 20)}px`;
      });
      col.addEventListener("mouseleave", () => { if (tip) tip.hidden = true; });
    });
  }

  return {
    load(force = false) {
      bindEventsOnce();
      if (!state.data || force) {
        loadRange(state.startMonth, state.endMonth);
      } else {
        updatePageMetaText();
      }
    },
  };
}
