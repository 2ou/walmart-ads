(() => {
  const state = {
    data: null,
    loading: false,
    selectedMonth: null,
    group: "parent",
    risk: "all",
    search: "",
    detailSearch: "",
    selectedParentSku: null,
    selectedColor: null,
    selectedSize: null,
    overviewPage: 0,
    detailPage: 0,
    pageSize: 50,
  };

  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];
  const fuzzySearchMatches = window.fuzzySearchMatches || ((query, ...values) => {
    const normalize = (value) => String(value ?? "").normalize("NFKC").toLocaleLowerCase("zh-CN").trim();
    const compact = (value) => normalize(value).replace(/[^\p{L}\p{N}]+/gu, "");
    const normalizedQuery = normalize(query);
    if (!normalizedQuery) return true;
    const haystack = normalize(values.flat(Infinity).filter((value) => value !== null && value !== undefined).join(" "));
    const compactHaystack = compact(haystack);
    const compactQuery = compact(normalizedQuery);
    if (compactQuery && compactHaystack.includes(compactQuery)) return true;
    return normalizedQuery.split(/\s+/).every((term) => haystack.includes(term) || compactHaystack.includes(compact(term)));
  });
  const number = (value) => Number.isFinite(Number(value)) ? Number(value) : 0;
  const integer = (value) => Math.round(number(value)).toLocaleString("zh-CN");
  const money = (value) => `$${number(value).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  const percent = (value, digits = 1) => `${(number(value) * 100).toFixed(digits)}%`;
  const escapeHtml = (value) => String(value ?? "").replace(/[&<>"]/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
  })[char]);
  const text = (value, fallback = "未填写") => value === null || value === undefined || value === "" ? fallback : String(value);
  const dateTime = (value) => value ? String(value).replace("T", " ").slice(0, 16) : "未填写";

  const riskCopy = {
    HIGH: { label: "高退款率", className: "high" },
    WATCH: { label: "观察", className: "watch" },
    NORMAL: { label: "正常", className: "normal" },
    NO_SALES_DENOMINATOR: { label: "缺少销量分母", className: "no-sales-denominator" },
  };

  const pageHtml = `
    <section class="view" id="view-returns" aria-labelledby="pageTitle">
      <div class="section-intro">
        <div>
          <h2>按退款发生月追踪款、颜色与尺码问题</h2>
          <p>申请件数与已完成退款分开统计。退款率使用同月订单销售件数作为分母，报表月份和实际申请月份同时保留。</p>
        </div>
        <label class="return-month-control" for="returnMonthSelect">查看月份
          <select id="returnMonthSelect" aria-label="选择退款月份"></select>
        </label>
      </div>
      <div class="status-banner" id="returnStatusBanner">
        <div class="status-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M8 7H4v-4"/><path d="M4 7a8 8 0 1 1-1 8"/></svg>
        </div>
        <div class="status-copy"><strong id="returnStatusHeadline">正在读取退款数据</strong><span id="returnStatusDetail">请稍候。</span></div>
        <div class="status-range mono" id="returnDateRange"></div>
      </div>
      <div class="return-metric-grid" id="returnMetricGrid"></div>
      <div class="return-layout">
        <section class="panel" aria-labelledby="returnTrendTitle">
          <div class="panel-header">
            <div><h2 class="panel-title" id="returnTrendTitle">月度退款率趋势</h2><p class="panel-subtitle">红线为申请退款率，蓝线为已完成退款率；鼠标悬停查看数值</p></div>
            <div class="legend">
              <span class="legend-item"><span class="legend-dot" style="background:var(--signal-red)"></span>申请退款率</span>
              <span class="legend-item"><span class="legend-dot"></span>已完成退款率</span>
            </div>
          </div>
          <div class="return-chart" id="returnTrendChart"></div>
        </section>
        <section class="panel" aria-labelledby="returnReasonTitle">
          <div class="panel-header"><div><h2 class="panel-title" id="returnReasonTitle">退款原因</h2><p class="panel-subtitle">按申请件数排序</p></div></div>
          <div class="return-breakdown-list" id="returnReasonList"></div>
        </section>
      </div>
      <section class="panel return-scope-panel" aria-labelledby="returnScopeTitle">
        <div class="panel-header">
          <div>
            <h2 class="panel-title" id="returnScopeTitle">款内实际退款色码分析</h2>
            <p class="panel-subtitle" id="returnScopeSubtitle">只统计已完成退款，申请中和已取消不进入退款率。</p>
          </div>
          <label class="return-parent-control" for="returnParentSelect">
            <span>分析款号</span>
            <select id="returnParentSelect" aria-label="选择要分析的款号"></select>
          </label>
        </div>
        <div class="return-scope-path">
          <span>当前范围</span>
          <strong id="returnScopePath">等待退款数据</strong>
          <button class="secondary-button return-scope-clear" id="returnScopeClear" type="button" hidden>清除颜色和尺码筛选</button>
        </div>
      </section>
      <div class="return-dimension-layout">
        <section class="panel" aria-labelledby="returnColorTitle">
          <div class="panel-header"><div><h2 class="panel-title" id="returnColorTitle">该款颜色实际退款表现</h2><p class="panel-subtitle" id="returnColorSubtitle">实际退款件数除以该款各颜色的同月销量</p></div></div>
          <div class="return-dimension-list" id="returnColorList"></div>
        </section>
        <section class="panel" aria-labelledby="returnSizeTitle">
          <div class="panel-header"><div><h2 class="panel-title" id="returnSizeTitle">该款尺码实际退款表现</h2><p class="panel-subtitle" id="returnSizeSubtitle">实际退款件数除以该款各尺码的同月销量</p></div></div>
          <div class="return-dimension-list" id="returnSizeList"></div>
        </section>
      </div>
      <section class="panel table-panel" aria-labelledby="returnOverviewTitle">
        <div class="panel-header">
          <div><h2 class="panel-title" id="returnOverviewTitle">退款率下钻</h2><p class="panel-subtitle" id="returnOverviewSubtitle">先按款号定位，再查看该款的 SKU</p></div>
          <div class="table-tools">
            <label class="sr-only" for="returnSearch">模糊搜索款号、SKU、Item ID、颜色或尺码</label>
            <input class="search-input" id="returnSearch" type="search" placeholder="模糊搜索款号、SKU、颜色或尺码，如 6066">
          </div>
        </div>
        <div class="panel-header">
          <div class="return-toolbar">
            <div class="sales-toolbar-group" id="returnGroupControls" role="group" aria-label="退款聚合维度">
              <button class="sales-segment-button active" type="button" data-return-group="parent" aria-pressed="true">按款号</button>
              <button class="sales-segment-button" type="button" data-return-group="sku" aria-pressed="false">按 SKU</button>
            </div>
            <div class="sales-toolbar-group" id="returnRiskFilters" role="group" aria-label="退款风险筛选">
              <button class="filter-button active" type="button" data-return-risk="all">全部</button>
              <button class="filter-button" type="button" data-return-risk="HIGH">高退款率</button>
              <button class="filter-button" type="button" data-return-risk="WATCH">观察</button>
              <button class="filter-button" type="button" data-return-risk="NO_SALES_DENOMINATOR">缺少分母</button>
            </div>
          </div>
        </div>
        <div class="table-scroll">
          <table class="return-table">
            <thead><tr><th>商品与维度</th><th>风险</th><th class="num">申请件数</th><th class="num">已退款</th><th class="num">已取消</th><th class="num">处理中</th><th class="num">同月销量</th><th class="num">申请退款率</th><th class="num">已完成退款率</th><th class="num">退款金额</th><th class="num">金额退款率</th></tr></thead>
            <tbody id="returnOverviewBody"></tbody>
          </table>
        </div>
        <div class="table-footer"><span id="returnOverviewInfo">0 条记录</span><div class="pagination"><button id="returnOverviewPrev" type="button">上一页</button><span id="returnOverviewPage">1 / 1</span><button id="returnOverviewNext" type="button">下一页</button></div></div>
      </section>
      <section class="panel table-panel" aria-labelledby="returnDetailTitle">
        <div class="panel-header">
          <div><h2 class="panel-title" id="returnDetailTitle">退款订单明细</h2><p class="panel-subtitle" id="returnDetailSubtitle">选择款号后查看对应退款订单</p></div>
          <div class="table-tools">
            <label class="sr-only" for="returnDetailSearch">模糊搜索订单号、RMA、SKU 或退款原因</label>
            <input class="search-input" id="returnDetailSearch" type="search" placeholder="模糊搜索订单号、RMA、SKU 或退款原因">
          </div>
        </div>
        <div class="table-scroll">
          <table class="return-table">
            <thead><tr><th>申请时间与订单</th><th>商品</th><th>原因</th><th>当前状态</th><th class="num">数量</th><th class="num">订单金额</th><th class="num">退款金额</th><th>退款方式</th><th>承担方</th><th>渠道</th><th>其他字段</th></tr></thead>
            <tbody id="returnDetailBody"></tbody>
          </table>
        </div>
        <div class="table-footer"><span id="returnDetailInfo">0 条记录</span><div class="pagination"><button id="returnDetailPrev" type="button">上一页</button><span id="returnDetailPage">1 / 1</span><button id="returnDetailNext" type="button">下一页</button></div></div>
      </section>
    </section>`;

  function install() {
    if ($("#view-returns")) return;
    const salesButton = $('[data-view="sales"]');
    if (!salesButton || !$(".content")) return;
    salesButton.insertAdjacentHTML("afterend", `
      <button class="nav-button" type="button" data-view="returns">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M8 7H4v-4"/><path d="M4 7a8 8 0 1 1-1 8"/><path d="M9 10h6M9 14h4"/></svg>
        <span>退款率分析</span>
      </button>`);
    $(".content").insertAdjacentHTML("beforeend", pageHtml);

    const returnButton = $('[data-view="returns"]');
    returnButton.addEventListener("click", activateReturns);
    $$(".nav-button:not([data-view='returns'])").forEach((button) => {
      button.addEventListener("click", () => document.body.classList.remove("returns-active"));
    });
    bindControls();
    if (location.hash === "#returns") activateReturns();
  }

  function activateReturns() {
    document.body.classList.add("returns-active");
    history.replaceState(null, "", "#returns");
    $$(".nav-button").forEach((button) => button.classList.toggle("active", button.dataset.view === "returns"));
    $$(".view").forEach((view) => view.classList.toggle("active", view.id === "view-returns"));
    $("#pageTitle").textContent = "退款率分析";
    syncPageMeta();
    document.body.classList.remove("sidebar-open");
    $("#menuButton")?.setAttribute("aria-expanded", "false");
    window.scrollTo({ top: 0, behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
    if (!state.data && !state.loading) loadReturns();
  }

  function syncPageMeta() {
    const expected = state.data?.meta?.selectedMonth
      ? `${state.data.meta.selectedMonth} · ${integer(state.data.summary.returnLines)} 条退款明细`
      : "按月查看退款申请、完成状态和款色码表现";
    if (document.body.classList.contains("returns-active") && $("#pageMeta")?.textContent !== expected) {
      $("#pageMeta").textContent = expected;
    }
  }

  function bindControls() {
    $("#returnMonthSelect").addEventListener("change", (event) => {
      state.selectedMonth = event.target.value;
      state.selectedParentSku = null;
      state.selectedColor = null;
      state.selectedSize = null;
      state.overviewPage = 0;
      state.detailPage = 0;
      loadReturns(state.selectedMonth);
    });
    $("#returnParentSelect").addEventListener("change", (event) => {
      selectParent(event.target.value, true);
    });
    $("#returnScopeClear").addEventListener("click", () => {
      state.selectedColor = null;
      state.selectedSize = null;
      state.overviewPage = 0;
      state.detailPage = 0;
      renderScopedAnalysis();
    });
    $("#returnSearch").addEventListener("input", (event) => {
      state.search = event.target.value;
      state.overviewPage = 0;
      renderOverview();
    });
    $("#returnDetailSearch").addEventListener("input", (event) => {
      state.detailSearch = event.target.value;
      state.detailPage = 0;
      renderDetails();
    });
    $$("[data-return-group]").forEach((button) => button.addEventListener("click", () => {
      state.group = button.dataset.returnGroup;
      state.overviewPage = 0;
      syncGroupButtons();
      renderOverview();
    }));
    $$("[data-return-risk]").forEach((button) => button.addEventListener("click", () => {
      state.risk = button.dataset.returnRisk;
      state.overviewPage = 0;
      $$("[data-return-risk]").forEach((item) => item.classList.toggle("active", item === button));
      renderOverview();
    }));
    $("#returnOverviewPrev").addEventListener("click", () => {
      state.overviewPage = Math.max(0, state.overviewPage - 1);
      renderOverview();
    });
    $("#returnOverviewNext").addEventListener("click", () => {
      state.overviewPage += 1;
      renderOverview();
    });
    $("#returnDetailPrev").addEventListener("click", () => {
      state.detailPage = Math.max(0, state.detailPage - 1);
      renderDetails();
    });
    $("#returnDetailNext").addEventListener("click", () => {
      state.detailPage += 1;
      renderDetails();
    });
  }

  async function loadReturns(month) {
    if (state.loading) return;
    state.loading = true;
    $("#returnStatusHeadline").textContent = "正在读取退款数据";
    $("#returnStatusDetail").textContent = "正在关联订单销量、Item ID、SKU、款号、颜色和尺码。";
    try {
      const query = month ? `?month=${encodeURIComponent(month)}` : "";
      const response = await fetch(`/api/returns/data${query}`);
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
      state.data = payload.data;
      state.selectedMonth = payload.data.meta.selectedMonth;
      ensureSelectedParent();
      render();
    } catch (error) {
      $("#returnStatusHeadline").textContent = "退款数据读取失败";
      $("#returnStatusDetail").textContent = error.message;
      $("#returnMetricGrid").innerHTML = "";
      $("#returnTrendChart").innerHTML = `<div class="empty-state"><strong>无法读取退款数据</strong>${escapeHtml(error.message)}</div>`;
    } finally {
      state.loading = false;
    }
  }

  function render() {
    const { meta, summary } = state.data;
    ensureSelectedParent();
    renderMonthSelect();
    if (!meta.selectedMonth) {
      $("#returnStatusHeadline").textContent = "还没有退款数据";
      $("#returnStatusDetail").textContent = "在数据导入页面执行一次扫描，系统会读取 source/Return_Orders 下的月度报表。";
      $("#returnDateRange").textContent = "";
      $("#returnMetricGrid").innerHTML = "";
      $("#returnTrendChart").innerHTML = `<div class="empty-state"><strong>等待退款报表</strong>扫描导入后，这里会显示月度退款率与款色码下钻。</div>`;
      $("#returnReasonList").innerHTML = "";
      $("#returnColorList").innerHTML = "";
      $("#returnSizeList").innerHTML = "";
      renderParentSelect();
      renderScopeContext();
      renderOverview();
      renderDetails();
      return;
    }
    $("#returnStatusHeadline").textContent = summary.soldUnits > 0
      ? `${meta.selectedMonth} 退款率已按真实订单销量计算`
      : `${meta.selectedMonth} 缺少同月订单销量分母`;
    const completeMonth = (meta.reportMonths || []).includes(meta.selectedMonth);
    $("#returnStatusDetail").textContent = completeMonth
      ? `已映射 ${percent(summary.mappingRate)} 的退款件数。已完成退款以 ${meta.completedDefinition} 判断。`
      : `当前月份只有相邻报表中的跨月零散记录，不能代表完整月度退款率。已映射 ${percent(summary.mappingRate)} 的退款件数。`;
    $("#returnDateRange").textContent = `${meta.startDate} 至 ${meta.endDate}`;
    syncPageMeta();
    $("#returnMetricGrid").innerHTML = [
      metric("申请退款率", percent(summary.requestRate), `${integer(summary.requestedUnits)} 件申请 ÷ ${integer(summary.soldUnits)} 件销量`, "alert"),
      metric("已完成退款率", percent(summary.completedRate), `${integer(summary.completedUnits)} 件已完成`, ""),
      metric("已退款金额", money(summary.refundAmount), `金额退款率 ${percent(summary.refundAmountRate)}`, "cost"),
      metric("处理中", `${integer(summary.pendingUnits)} 件`, `已取消 ${integer(summary.cancelledUnits)} 件`, ""),
      metric("卖家承担", money(summary.sellerAmount), `沃尔玛承担 ${money(summary.walmartAmount)}`, "cost"),
      metric("受影响范围", `${integer(summary.affectedParents)} 款`, `${integer(summary.affectedSkus)} 个已映射 SKU`, ""),
    ].join("");
    renderTrend();
    renderBreakdown(state.data.reasons, "#returnReasonList");
    renderParentSelect();
    renderScopeContext();
    renderSelectedDimensions();
    syncGroupButtons();
    renderOverview();
    renderDetails();
  }

  function metric(label, value, note, className) {
    return `<article class="return-metric ${className}"><span>${label}</span><strong>${value}</strong><small>${note}</small></article>`;
  }

  function renderMonthSelect() {
    const select = $("#returnMonthSelect");
    const months = (state.data?.trend || [])
      .filter((row) => row.month >= state.data.meta.firstMonth && row.month <= state.data.meta.latestMonth)
      .map((row) => row.month);
    if (!months.length) {
      select.innerHTML = `<option value="">暂无月份</option>`;
      select.disabled = true;
      return;
    }
    select.disabled = false;
    const completeMonths = new Set(state.data.meta.reportMonths || []);
    select.innerHTML = [...new Set(months)].reverse()
      .map((month) => `<option value="${month}" ${month === state.selectedMonth ? "selected" : ""}>${month}${completeMonths.has(month) ? "" : "（跨月零散）"}</option>`)
      .join("");
  }

  function parentRows() {
    return (state.data?.parentRows || [])
      .filter((row) => number(row.completedUnits) > 0)
      .sort((a, b) => number(b.completedUnits) - number(a.completedUnits)
        || number(b.completedRate) - number(a.completedRate)
        || text(a.parentSku).localeCompare(text(b.parentSku)));
  }

  function ensureSelectedParent() {
    const parents = parentRows();
    if (!parents.length) {
      state.selectedParentSku = null;
      state.selectedColor = null;
      state.selectedSize = null;
      return;
    }
    if (parents.some((row) => row.parentSku === state.selectedParentSku)) return;
    const priority = [...parents].sort((a, b) => number(b.completedRate) - number(a.completedRate)
      || number(b.completedUnits) - number(a.completedUnits))[0];
    state.selectedParentSku = priority.parentSku;
    state.selectedColor = null;
    state.selectedSize = null;
  }

  function selectedParentRow() {
    return parentRows().find((row) => row.parentSku === state.selectedParentSku) || null;
  }

  function selectedParentBreakdown() {
    return (state.data?.parentBreakdowns || [])
      .find((item) => item.parentSku === state.selectedParentSku) || null;
  }

  function renderParentSelect() {
    const select = $("#returnParentSelect");
    if (!select) return;
    const parents = parentRows();
    if (!parents.length) {
      select.innerHTML = `<option value="">暂无可分析款号</option>`;
      select.disabled = true;
      return;
    }
    select.disabled = false;
    select.innerHTML = parents.map((row) => `
      <option value="${escapeHtml(row.parentSku)}" ${row.parentSku === state.selectedParentSku ? "selected" : ""}>
        ${escapeHtml(row.parentSku)} · ${integer(row.completedUnits)} 件已退款 · ${percent(row.completedRate)}
      </option>`).join("");
  }

  function renderScopeContext() {
    const row = selectedParentRow();
    const month = state.data?.meta?.selectedMonth;
    const path = [month, row?.parentSku, state.selectedColor, state.selectedSize].filter(Boolean).join(" › ");
    $("#returnScopePath").textContent = path || "等待选择款号";
    $("#returnScopeSubtitle").textContent = row
      ? `${row.parentSku} 共 ${integer(row.completedUnits)} 件已退款，颜色和尺码均按实际退款件数计算。`
      : "只统计已完成退款，申请中和已取消不进入退款率。";
    $("#returnColorSubtitle").textContent = row
      ? `${row.parentSku} · 每个颜色的实际退款件数 ÷ 该颜色销量`
      : "实际退款件数除以该款各颜色的同月销量";
    $("#returnSizeSubtitle").textContent = row
      ? `${row.parentSku} · 每个尺码的实际退款件数 ÷ 该尺码销量`
      : "实际退款件数除以该款各尺码的同月销量";
    $("#returnScopeClear").hidden = !state.selectedColor && !state.selectedSize;
  }

  function renderSelectedDimensions() {
    const breakdown = selectedParentBreakdown();
    renderDimensions(breakdown?.colors || [], "#returnColorList", "color", state.selectedColor);
    renderDimensions(breakdown?.sizes || [], "#returnSizeList", "size", state.selectedSize);
  }

  function renderScopedAnalysis() {
    renderParentSelect();
    renderScopeContext();
    renderSelectedDimensions();
    syncGroupButtons();
    renderOverview();
    renderDetails();
  }

  function selectParent(parentSku, drillToSku) {
    if (!parentRows().some((row) => row.parentSku === parentSku)) return;
    state.selectedParentSku = parentSku;
    state.selectedColor = null;
    state.selectedSize = null;
    state.overviewPage = 0;
    state.detailPage = 0;
    if (drillToSku) state.group = "sku";
    renderScopedAnalysis();
  }

  function selectDimension(key, label) {
    if (key === "color") {
      state.selectedColor = state.selectedColor === label ? null : label;
    } else {
      state.selectedSize = state.selectedSize === label ? null : label;
    }
    state.group = "sku";
    state.overviewPage = 0;
    state.detailPage = 0;
    renderScopedAnalysis();
  }

  function syncGroupButtons() {
    $$("[data-return-group]").forEach((item) => {
      const active = item.dataset.returnGroup === state.group;
      item.classList.toggle("active", active);
      item.setAttribute("aria-pressed", String(active));
    });
  }

  function matchesScope(row) {
    if (!state.selectedParentSku || row.parentSku !== state.selectedParentSku) return false;
    if (state.selectedColor && text(row.color, "未识别") !== state.selectedColor) return false;
    if (state.selectedSize && text(row.size, "未识别") !== state.selectedSize) return false;
    return true;
  }

  function scopedSkuRows() {
    return (state.data?.skuRows || []).filter(matchesScope);
  }

  function scopedDetailRows() {
    return (state.data?.details || []).filter(matchesScope);
  }

  function renderTrend() {
    const chart = $("#returnTrendChart");
    const rows = (state.data.trend || []).filter((row) => (
      row.month >= state.data.meta.firstMonth && row.month <= state.data.meta.latestMonth
    ));
    if (!rows.length) {
      chart.innerHTML = `<div class="empty-state"><strong>没有月度趋势</strong>当前退款明细还不能形成月份序列。</div>`;
      return;
    }
    const width = 900;
    const height = 284;
    const left = 58;
    const right = 30;
    const top = 24;
    const bottom = 42;
    const plotWidth = width - left - right;
    const plotHeight = height - top - bottom;
    const maxRate = Math.max(...rows.flatMap((row) => [number(row.requestRate), number(row.completedRate)]), .01) * 1.15;
    const xFor = (index) => rows.length === 1 ? left + plotWidth / 2 : left + index / (rows.length - 1) * plotWidth;
    const points = (key) => rows.map((row, index) => ({
      x: xFor(index),
      y: top + plotHeight - number(row[key]) / maxRate * plotHeight,
    }));
    const requestPoints = points("requestRate");
    const completePoints = points("completedRate");
    const path = (items) => items.map((item, index) => `${index ? "L" : "M"}${item.x.toFixed(1)},${item.y.toFixed(1)}`).join(" ");
    const area = `${path(requestPoints)} L${requestPoints.at(-1).x.toFixed(1)},${top + plotHeight} L${requestPoints[0].x.toFixed(1)},${top + plotHeight} Z`;
    const grid = [0, .25, .5, .75, 1].map((step) => {
      const y = top + plotHeight * step;
      return `<line class="chart-grid-line" x1="${left}" x2="${left + plotWidth}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${percent(maxRate * (1 - step), 0)}</text>`;
    }).join("");
    const labels = rows.map((row, index) => `<text class="chart-axis-label" x="${xFor(index)}" y="${height - 12}" text-anchor="middle">${row.month.slice(5)}月</text>`).join("");
    chart.innerHTML = `
      <svg viewBox="0 0 ${width} ${height}" role="img" aria-label="月度申请退款率与已完成退款率趋势">
        ${grid}<path class="return-chart-area" d="${area}"/><path class="return-chart-line-request" d="${path(requestPoints)}"/><path class="return-chart-line-complete" d="${path(completePoints)}"/>${labels}
        <line class="return-chart-crosshair" id="returnTrendCrosshair" x1="${left}" x2="${left}" y1="${top}" y2="${top + plotHeight}" hidden/>
        <g id="returnTrendMarkers"></g>
        <rect class="return-chart-hit" id="returnTrendHit" x="${left}" y="${top}" width="${plotWidth}" height="${plotHeight}" fill="transparent" tabindex="0" role="slider" aria-label="退款月份，使用左右方向键查看" aria-valuemin="1" aria-valuemax="${rows.length}" aria-valuenow="${rows.length}"/>
      </svg>
      <div class="return-chart-tooltip" id="returnTrendTooltip" role="status" aria-live="polite"></div>`;
    const svg = chart.querySelector("svg");
    const hit = $("#returnTrendHit");
    const crosshair = $("#returnTrendCrosshair");
    const markers = $("#returnTrendMarkers");
    const tooltip = $("#returnTrendTooltip");
    let active = rows.length - 1;
    const show = (index) => {
      active = Math.max(0, Math.min(rows.length - 1, index));
      const row = rows[active];
      const request = requestPoints[active];
      const complete = completePoints[active];
      crosshair.setAttribute("x1", request.x);
      crosshair.setAttribute("x2", request.x);
      crosshair.hidden = false;
      markers.innerHTML = `<circle class="return-chart-marker" cx="${request.x}" cy="${request.y}" r="4.5" fill="var(--signal-red)"/><circle class="return-chart-marker" cx="${complete.x}" cy="${complete.y}" r="4.5" fill="var(--walmart-blue)"/>`;
      tooltip.innerHTML = `<strong>${row.month}</strong><div class="return-tooltip-row"><span>申请退款率</span><b>${percent(row.requestRate)}</b></div><div class="return-tooltip-row"><span>已完成退款率</span><b>${percent(row.completedRate)}</b></div><div class="return-tooltip-row"><span>申请 / 销量</span><b>${integer(row.requestedUnits)} / ${integer(row.soldUnits)}</b></div><div class="return-tooltip-row"><span>退款金额</span><b>${money(row.refundAmount)}</b></div>`;
      const x = request.x / width * chart.clientWidth;
      tooltip.style.left = `${Math.max(110, Math.min(chart.clientWidth - 110, x))}px`;
      tooltip.classList.add("show");
      hit.setAttribute("aria-valuenow", String(active + 1));
      hit.setAttribute("aria-valuetext", `${row.month}，申请退款率 ${percent(row.requestRate)}，已完成退款率 ${percent(row.completedRate)}`);
    };
    const hide = () => {
      crosshair.hidden = true;
      markers.innerHTML = "";
      tooltip.classList.remove("show");
    };
    const pointerIndex = (event) => {
      const rect = svg.getBoundingClientRect();
      const x = (event.clientX - rect.left) / rect.width * width;
      return requestPoints.reduce((nearest, point, index) => (
        Math.abs(point.x - x) < Math.abs(requestPoints[nearest].x - x) ? index : nearest
      ), 0);
    };
    hit.addEventListener("pointermove", (event) => show(pointerIndex(event)));
    hit.addEventListener("pointerdown", (event) => show(pointerIndex(event)));
    hit.addEventListener("pointerleave", () => {
      if (document.activeElement !== hit) hide();
    });
    hit.addEventListener("focus", () => show(active));
    hit.addEventListener("blur", hide);
    hit.addEventListener("keydown", (event) => {
      if (["ArrowLeft", "ArrowRight", "Home", "End", "Escape"].includes(event.key)) event.preventDefault();
      if (event.key === "ArrowLeft") show(active - 1);
      if (event.key === "ArrowRight") show(active + 1);
      if (event.key === "Home") show(0);
      if (event.key === "End") show(rows.length - 1);
      if (event.key === "Escape") hide();
    });
  }

  function renderBreakdown(rows, target) {
    const items = (rows || []).slice(0, 9);
    if (!items.length) {
      $(target).innerHTML = `<div class="empty-state"><strong>当前月份没有退款原因</strong>导入退款明细后会按件数排序。</div>`;
      return;
    }
    const max = Math.max(...items.map((row) => number(row.units)), 1);
    $(target).innerHTML = items.map((row) => `
      <div class="return-breakdown-row">
        <div class="return-breakdown-copy"><span title="${escapeHtml(row.label)}">${escapeHtml(row.label)}</span><strong>${integer(row.units)} 件 · ${percent(row.share)}</strong></div>
        <div class="return-breakdown-track"><i style="width:${Math.max(3, number(row.units) / max * 100)}%"></i></div>
      </div>`).join("");
  }

  function renderDimensions(rows, target, key, selectedLabel) {
    const container = $(target);
    const items = (rows || []).slice(0, 10);
    if (!items.length) {
      container.innerHTML = `<div class="empty-state"><strong>该款没有可归集的${key === "color" ? "颜色" : "尺码"}数据</strong>请检查产品上传表中的款号、SKU 与${key === "color" ? "颜色" : "尺码"}字段。</div>`;
      return;
    }
    container.innerHTML = items.map((row) => `
      <button class="return-dimension-row ${row.label === selectedLabel ? "selected" : ""}" type="button"
        data-return-dimension-key="${key}" data-return-dimension-label="${escapeHtml(row.label)}"
        aria-pressed="${row.label === selectedLabel}">
        <strong title="${escapeHtml(row.label)}">${escapeHtml(row.label)}</strong>
        <span><b>${integer(row.completedUnits)} / ${integer(row.soldUnits)}</b><small>已退款 / 销量</small></span>
        <span><b>${percent(row.completedRate)}</b><small>实际退款率</small></span>
        <span><b>${percent(row.shareOfParentCompletedRefunds)}</b><small>占本款已退款</small></span>
      </button>`).join("");
    container.querySelectorAll("[data-return-dimension-key]").forEach((button) => {
      button.addEventListener("click", () => selectDimension(
        button.dataset.returnDimensionKey,
        button.dataset.returnDimensionLabel
      ));
    });
  }

  function overviewRows() {
    if (!state.data) return [];
    if (state.group === "parent") return state.data.parentRows || [];
    return scopedSkuRows();
  }

  function renderOverview() {
    const body = $("#returnOverviewBody");
    if (!body) return;
    let rows = overviewRows();
    if (state.risk !== "all") rows = rows.filter((row) => row.risk === state.risk);
    if (state.search) {
      rows = rows.filter((row) => fuzzySearchMatches(state.search, [
        row.parentSku, row.sku, row.itemId, row.groupKey, row.label, row.itemName, row.color, row.size,
      ]));
    }
    const pages = Math.max(1, Math.ceil(rows.length / state.pageSize));
    state.overviewPage = Math.min(state.overviewPage, pages - 1);
    const pageRows = rows.slice(state.overviewPage * state.pageSize, (state.overviewPage + 1) * state.pageSize);
    $("#returnOverviewSubtitle").textContent = state.group === "parent"
      ? "按款号定位问题款；点击款号后查看该款的 SKU、颜色、尺码与订单明细。"
      : `${state.selectedParentSku || "未选择款号"} 的 SKU，退款率分母包含该款同月有销量但没有退款的变体。`;
    if (!pageRows.length) {
      body.innerHTML = `<tr><td colspan="11"><div class="empty-state"><strong>没有符合条件的退款记录</strong>调整月份、维度、风险或搜索条件后重试。</div></td></tr>`;
    } else {
      body.innerHTML = pageRows.map((row) => {
        const key = text(row.parentSku || row.sku || row.label || row.itemId, "未映射");
        const detail = state.group === "parent"
          ? `${integer(row.skuCount)} 个 SKU · ${integer(row.colorCount)} 色 · ${integer(row.sizeCount)} 码`
          : state.group === "sku"
            ? `${text(row.color)} · ${text(row.size)} · Item ${text(row.itemId)}`
            : `${integer(row.skuCount)} 个 SKU`;
        const image = row.imageUrl
          ? `<img class="return-product-thumb" src="${escapeHtml(row.imageUrl)}" alt="" loading="lazy">`
          : `<span class="return-product-thumb" aria-hidden="true"></span>`;
        const risk = riskCopy[row.risk] || riskCopy.NORMAL;
        const selected = state.group === "parent" && row.parentSku === state.selectedParentSku;
        const keyMarkup = state.group === "parent"
          ? `<button class="return-parent-button" type="button" data-return-parent="${escapeHtml(row.parentSku)}" aria-current="${selected ? "true" : "false"}" title="查看 ${escapeHtml(key)} 的款内色码分析">${escapeHtml(key)}</button>`
          : `<strong title="${escapeHtml(key)}">${escapeHtml(key)}</strong>`;
        return `<tr class="${selected ? "return-selected-row" : ""}">
          <td><div class="return-product-cell">${image}<div>${keyMarkup}<span title="${escapeHtml(row.itemName || detail)}">${escapeHtml(row.itemName || detail)}</span><span>${escapeHtml(detail)}</span></div></div></td>
          <td><span class="return-risk ${risk.className}">${risk.label}</span></td>
          <td class="num">${integer(row.requestedUnits)}</td>
          <td class="num">${integer(row.completedUnits)}</td>
          <td class="num">${integer(row.cancelledUnits)}</td>
          <td class="num">${integer(row.pendingUnits)}</td>
          <td class="num">${integer(row.soldUnits)}</td>
          <td class="num">${percent(row.requestRate)}</td>
          <td class="num">${percent(row.completedRate)}</td>
          <td class="num">${money(row.refundAmount)}</td>
          <td class="num">${percent(row.refundAmountRate)}</td>
        </tr>`;
      }).join("");
      body.querySelectorAll("[data-return-parent]").forEach((button) => {
        button.addEventListener("click", () => selectParent(button.dataset.returnParent, true));
      });
    }
    $("#returnOverviewInfo").textContent = `${integer(rows.length)} 条结果，当前显示 ${integer(pageRows.length)} 条`;
    $("#returnOverviewPage").textContent = `${state.overviewPage + 1} / ${pages}`;
    $("#returnOverviewPrev").disabled = state.overviewPage === 0;
    $("#returnOverviewNext").disabled = state.overviewPage >= pages - 1;
  }

  function detailStatus(status) {
    const value = String(status || "");
    if (value.startsWith("Refunded:")) return { label: "已退款", className: "scale" };
    if (value.startsWith("Cancelled:")) return { label: "已取消", className: "collect" };
    return { label: "处理中", className: "optimize" };
  }

  function renderDetails() {
    const body = $("#returnDetailBody");
    if (!body) return;
    const path = [state.selectedParentSku, state.selectedColor, state.selectedSize].filter(Boolean).join(" › ");
    $("#returnDetailSubtitle").textContent = path
      ? `${path}；客户姓名和邮箱不在经营页面展示。`
      : "选择款号后查看对应退款订单。";
    let rows = scopedDetailRows();
    if (state.detailSearch) {
      rows = rows.filter((row) => fuzzySearchMatches(state.detailSearch, [
        row.customerOrderNo, row.poNo, row.rmaNo, row.sku, row.parentSku, row.itemId,
        row.itemName, row.returnReason, row.currentStatus, row.refundStatus, row.color, row.size,
        row.refundMode, row.refundCoveredBy, row.channel, row.trackingNumber, row.sellerOrderNo,
      ]));
    }
    const pages = Math.max(1, Math.ceil(rows.length / state.pageSize));
    state.detailPage = Math.min(state.detailPage, pages - 1);
    const pageRows = rows.slice(state.detailPage * state.pageSize, (state.detailPage + 1) * state.pageSize);
    if (!pageRows.length) {
      body.innerHTML = `<tr><td colspan="11"><div class="empty-state"><strong>当前款内范围没有退款订单明细</strong>清除颜色、尺码或搜索条件后重试。</div></td></tr>`;
    } else {
      body.innerHTML = pageRows.map((row) => {
        const status = detailStatus(row.currentStatus);
        const image = row.imageUrl
          ? `<img class="return-product-thumb" src="${escapeHtml(row.imageUrl)}" alt="" loading="lazy">`
          : `<span class="return-product-thumb" aria-hidden="true"></span>`;
        const sku = text(row.sku, `Item ${text(row.itemId)}`);
        return `<tr>
          <td><div class="return-detail-meta"><strong>${escapeHtml(dateTime(row.requestAt))}</strong><span>订单 ${escapeHtml(row.customerOrderNo)}</span><span>RMA ${escapeHtml(row.rmaNo)}</span></div></td>
          <td><div class="return-product-cell">${image}<div><strong title="${escapeHtml(sku)}">${escapeHtml(sku)}</strong><span>${escapeHtml(text(row.parentSku))} · ${escapeHtml(text(row.color))} · ${escapeHtml(text(row.size))}</span><span title="${escapeHtml(row.itemName)}">${escapeHtml(text(row.itemName))}</span></div></div></td>
          <td title="${escapeHtml(row.returnReason)}">${escapeHtml(text(row.returnReason))}</td>
          <td><span class="status-badge ${status.className}">${status.label}</span><div class="mono" style="margin-top:5px;color:var(--steel-copy);font-size:10px">${escapeHtml(text(row.currentStatus))}</div></td>
          <td class="num">${integer(row.quantity)}</td>
          <td class="num">${money(row.orderAmount)}</td>
          <td class="num">${money(row.totalRefundAmount)}</td>
          <td>${escapeHtml(text(row.refundMode))}</td>
          <td>${escapeHtml(text(row.refundCoveredBy))}</td>
          <td>${escapeHtml(text(row.channel))}</td>
          <td><details class="return-more"><summary>查看字段</summary><div>
            <b>报表月份：</b>${escapeHtml(text(row.reportMonth))}<br>
            <b>PO / 行：</b>${escapeHtml(text(row.poNo))} / ${escapeHtml(text(row.poLineNo))}<br>
            <b>退货行：</b>${escapeHtml(text(row.returnOrderLineNo))}<br>
            <b>Seller Order：</b>${escapeHtml(text(row.sellerOrderNo))}<br>
            <b>退款状态：</b>${escapeHtml(text(row.refundStatus))}<br>
            <b>状态更新时间：</b>${escapeHtml(dateTime(row.currentStatusTime))}<br>
            <b>商品状态：</b>${escapeHtml(text(row.condition))}<br>
            <b>承运商 / 物流：</b>${escapeHtml(text(row.carrier))} / ${escapeHtml(text(row.trackingStatus))}<br>
            <b>Tracking：</b>${escapeHtml(text(row.trackingNumber))}<br>
            <b>退货运费 / 补货费：</b>${money(row.returnShippingFee)} / ${money(row.restockingFee)}<br>
            <b>Keep It：</b>${row.keepIt ? "是" : "否"}　<b>换货：</b>${row.replacement ? "是" : "否"}<br>
            <b>平台：</b>${escapeHtml(text(row.platformType))}<br>
            <b>退货地：</b>${escapeHtml(text(row.returnShipFrom))} → ${escapeHtml(text(row.returnShipTo))}<br>
            <b>客户备注：</b>${escapeHtml(text(row.customerComments))}
          </div></details></td>
        </tr>`;
      }).join("");
    }
    $("#returnDetailInfo").textContent = `${integer(rows.length)} 条结果，当前显示 ${integer(pageRows.length)} 条`;
    $("#returnDetailPage").textContent = `${state.detailPage + 1} / ${pages}`;
    $("#returnDetailPrev").disabled = state.detailPage === 0;
    $("#returnDetailNext").disabled = state.detailPage >= pages - 1;
  }

  function waitForShell(attempt = 0) {
    const shellReady = $("#sidebarSourceTime")?.textContent;
    if (shellReady || attempt >= 100) {
      install();
      return;
    }
    setTimeout(() => waitForShell(attempt + 1), 50);
  }

  const pageMetaObserver = new MutationObserver(syncPageMeta);
  const observePageMeta = () => {
    if ($("#pageMeta")) pageMetaObserver.observe($("#pageMeta"), { childList: true, subtree: true, characterData: true });
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => {
      observePageMeta();
      waitForShell();
    }, { once: true });
  } else {
    observePageMeta();
    waitForShell();
  }
})();
