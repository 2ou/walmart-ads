(() => {
  const state = {
    data: null,
    loading: false,
    selectedMonth: null,
    group: "parent",
    risk: "all",
    search: "",
    detailSearch: "",
    trendSelectedParents: new Set(),
    trendSearch: "",
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
  const styleNumberCollator = new Intl.Collator("zh-CN", { numeric: true, sensitivity: "base" });
  const compareStyleNo = (left, right) => styleNumberCollator.compare(String(left ?? ""), String(right ?? ""));
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
      <section class="return-account-rollup" aria-labelledby="returnAccountRollupTitle">
        <div class="return-account-rollup-lead">
          <span>全部已导入月份 · 整个账号</span>
          <div>
            <strong id="returnAccountCompletedRate">--</strong>
            <h2 id="returnAccountRollupTitle">累计实际退款率</h2>
          </div>
          <p id="returnAccountRollupNote">等待退款数据。</p>
        </div>
        <dl class="return-account-rollup-stats">
          <div><dt>已退款 / 销量</dt><dd id="returnAccountUnits">--</dd></div>
          <div><dt>累计退款金额</dt><dd id="returnAccountRefundAmount">--</dd></div>
          <div><dt>覆盖月份</dt><dd id="returnAccountMonths">--</dd></div>
        </dl>
      </section>
      <div class="return-metric-grid" id="returnMetricGrid"></div>
      <div class="return-layout">
        <section class="panel return-trend-panel" aria-labelledby="returnTrendTitle">
          <div class="panel-header">
            <div><h2 class="panel-title" id="returnTrendTitle">月度退款率趋势</h2><p class="panel-subtitle" id="returnTrendSubtitle">默认显示店铺总体退款率，可按需叠加款号</p></div>
            <div class="return-trend-header-actions">
              <details class="return-trend-series-control" id="returnTrendSeriesControl">
                <summary>
                  <span>筛选款号</span>
                  <strong id="returnTrendSeriesSummary">仅店铺总体</strong>
                </summary>
                <div class="return-trend-series-panel">
                  <div class="return-trend-series-actions">
                    <span>店铺总体始终显示，勾选款号后叠加对比</span>
                    <button type="button" id="returnTrendClearSelected">清空款号</button>
                  </div>
                  <label class="return-trend-series-search" for="returnTrendSeriesSearch">
                    <span class="sr-only">搜索趋势款号</span>
                    <input id="returnTrendSeriesSearch" type="search" placeholder="搜索款号，如 PNK366076" autocomplete="off">
                  </label>
                  <div class="return-trend-series-options" id="returnTrendSeriesOptions"></div>
                </div>
              </details>
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
            <thead><tr><th>商品与维度</th><th>风险</th><th class="num">申请件数</th><th class="num">已退款</th><th class="num">已取消</th><th class="num">处理中</th><th class="num">同月销量</th><th class="num">本月退款率</th><th class="num" id="returnTotalRateHeader">款号累计总退款率</th><th class="num">退款金额</th><th class="num">金额退款率</th></tr></thead>
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
    $("#returnTrendSeriesSearch").addEventListener("input", (event) => {
      state.trendSearch = event.target.value;
      renderTrendSeriesControl();
    });
    document.addEventListener("click", (event) => {
      const control = $("#returnTrendSeriesControl");
      if (control?.open && !control.contains(event.target)) control.open = false;
    });
    document.addEventListener("keydown", (event) => {
      if (event.key !== "Escape") return;
      const control = $("#returnTrendSeriesControl");
      if (!control?.open) return;
      control.open = false;
      control.querySelector("summary")?.focus();
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
    const { meta, summary, accountSummary } = state.data;
    ensureSelectedParent();
    renderMonthSelect();
    if (!meta.selectedMonth) {
      $("#returnStatusHeadline").textContent = "还没有退款数据";
      $("#returnStatusDetail").textContent = "在数据导入页面执行一次扫描，系统会读取 source/Return_Orders 下的月度报表。";
      $("#returnDateRange").textContent = "";
      renderAccountSummary();
      $("#returnMetricGrid").innerHTML = "";
      $("#returnTrendChart").innerHTML = `<div class="empty-state"><strong>等待退款报表</strong>扫描导入后，这里会显示月度退款率与款色码下钻。</div>`;
      renderTrendSeriesControl();
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
    renderAccountSummary(accountSummary);
    $("#returnMetricGrid").innerHTML = [
      metric("已完成退款率", percent(summary.completedRate), `${integer(summary.completedUnits)} 件已完成`, "alert"),
      metric("已退款金额", money(summary.refundAmount), `金额退款率 ${percent(summary.refundAmountRate)}`, "cost"),
      metric("处理中", `${integer(summary.pendingUnits)} 件`, `已取消 ${integer(summary.cancelledUnits)} 件`, ""),
      metric("卖家承担", money(summary.sellerAmount), `沃尔玛承担 ${money(summary.walmartAmount)}`, "cost"),
      metric("受影响范围", `${integer(summary.affectedParents)} 款`, `${integer(summary.affectedSkus)} 个已映射 SKU`, ""),
    ].join("");
    renderTrendSeriesControl();
    renderTrend();
    renderBreakdown(state.data.reasons, "#returnReasonList");
    renderParentSelect();
    renderScopeContext();
    renderSelectedDimensions();
    syncGroupButtons();
    renderOverview();
    renderDetails();
  }

  function renderAccountSummary(account = state.data?.accountSummary) {
    const rate = $("#returnAccountCompletedRate");
    const note = $("#returnAccountRollupNote");
    const units = $("#returnAccountUnits");
    const refundAmount = $("#returnAccountRefundAmount");
    const months = $("#returnAccountMonths");
    if (!account || !number(account.monthCount)) {
      rate.textContent = "--";
      note.textContent = "导入月度退款报表后，这里会汇总整个账号。";
      units.textContent = "--";
      refundAmount.textContent = "--";
      months.textContent = "--";
      return;
    }
    rate.textContent = account.soldUnits > 0 ? percent(account.completedRate) : "缺少分母";
    note.textContent = `${integer(account.monthCount)} 个已导入月份按累计件数计算，不取各月退款率的平均值。`;
    units.textContent = `${integer(account.completedUnits)} / ${integer(account.soldUnits)}`;
    refundAmount.textContent = money(account.refundAmount);
    months.textContent = account.firstMonth === account.lastMonth
      ? text(account.firstMonth)
      : `${text(account.firstMonth)} 至 ${text(account.lastMonth)}`;
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

  function trendParents() {
    const parents = new Map();
    (state.data?.parentTrend || []).forEach((row) => {
      if (!row.parentSku) return;
      const current = parents.get(row.parentSku);
      if (!current || text(current.itemName) === current.parentSku) {
        parents.set(row.parentSku, {
          parentSku: row.parentSku,
          itemName: text(row.itemName, row.parentSku),
        });
      }
    });
    return [...parents.values()].sort((a, b) => compareStyleNo(a.parentSku, b.parentSku));
  }

  const trendPalette = [
    "var(--return-trend-1)",
    "var(--return-trend-3)",
    "var(--return-trend-4)",
    "var(--return-trend-5)",
    "var(--return-trend-6)",
  ];

  function trendSeriesStyle(index) {
    const patternGroup = Math.floor(index / trendPalette.length) % 3;
    return {
      color: trendPalette[index % trendPalette.length],
      dash: patternGroup === 1 ? "7 4" : patternGroup === 2 ? "2 4" : "none",
      dashClass: patternGroup === 1 ? "dashed" : patternGroup === 2 ? "dotted" : "",
    };
  }

  function renderTrendSeriesControl() {
    const control = $("#returnTrendSeriesControl");
    const options = $("#returnTrendSeriesOptions");
    if (!control || !options) return;
    const parents = trendParents();
    const available = new Set(parents.map((row) => row.parentSku));
    state.trendSelectedParents = new Set(
      [...state.trendSelectedParents].filter((parentSku) => available.has(parentSku))
    );
    const selectedCount = state.trendSelectedParents.size;
    const filteredParents = parents.filter((row) => fuzzySearchMatches(state.trendSearch, row.parentSku));
    $("#returnTrendSeriesSummary").textContent = selectedCount
      ? `店铺 + ${selectedCount} 个款号`
      : "仅店铺总体";
    control.classList.toggle("disabled", !parents.length);
    options.innerHTML = filteredParents.length
      ? filteredParents.map((row) => {
        const index = parents.findIndex((parent) => parent.parentSku === row.parentSku);
        const style = trendSeriesStyle(index);
        const checked = state.trendSelectedParents.has(row.parentSku);
        return `<label class="return-trend-series-option">
          <input type="checkbox" data-return-trend-parent="${escapeHtml(row.parentSku)}" ${checked ? "checked" : ""}>
          <span class="return-trend-series-swatch ${style.dashClass}" style="--series-color:${style.color}" aria-hidden="true"></span>
          <span class="mono">${escapeHtml(row.parentSku)}</span>
        </label>`;
      }).join("")
      : `<div class="return-trend-series-empty">${parents.length ? "没有匹配的款号" : "产品库暂无可展示款号"}</div>`;
    options.querySelectorAll("[data-return-trend-parent]").forEach((checkbox) => {
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) state.trendSelectedParents.add(checkbox.dataset.returnTrendParent);
        else state.trendSelectedParents.delete(checkbox.dataset.returnTrendParent);
        renderTrendSeriesControl();
        renderTrend();
      });
    });
    const clearButton = $("#returnTrendClearSelected");
    clearButton.disabled = selectedCount === 0;
    clearButton.onclick = () => {
      state.trendSelectedParents.clear();
      renderTrendSeriesControl();
      renderTrend();
    };
  }

  function parentRows() {
    return (state.data?.parentRows || [])
      .filter((row) => number(row.completedUnits) > 0)
      .sort((a, b) => compareStyleNo(a.parentSku, b.parentSku));
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
        ${escapeHtml(row.parentSku)} · 本月 ${percent(row.completedRate)} · 累计 ${row.totalSoldUnits > 0 ? percent(row.totalCompletedRate) : "缺少分母"}
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
    const accountRows = (state.data.trend || []).filter((row) => (
      row.month >= state.data.meta.firstMonth && row.month <= state.data.meta.latestMonth
    ));
    const parents = trendParents();
    const visibleParents = parents.filter((row) => state.trendSelectedParents.has(row.parentSku));
    $("#returnTrendSubtitle").textContent = visibleParents.length
      ? `店铺总体与 ${visibleParents.length} 个已选款号对比`
      : "店铺总体已完成退款率，使用全部订单销量作为分母";
    if (!accountRows.length) {
      chart.innerHTML = `<div class="empty-state"><strong>没有月度趋势</strong>当前退款明细还不能形成月份序列。</div>`;
      return;
    }
    const parentTrend = state.data.parentTrend || [];
    const parentSeries = visibleParents.map((parent) => {
      const byMonth = new Map(parentTrend
        .filter((row) => row.parentSku === parent.parentSku)
        .map((row) => [row.month, row]));
      return {
        ...parent,
        ...trendSeriesStyle(parents.findIndex((row) => row.parentSku === parent.parentSku)),
        label: parent.parentSku,
        account: false,
        rows: accountRows.map((accountRow) => byMonth.get(accountRow.month) || {
          parentSku: parent.parentSku,
          month: accountRow.month,
          completedRate: 0,
        }),
      };
    });
    const series = [{
      label: "店铺总体",
      color: "var(--walmart-blue)",
      dash: "none",
      dashClass: "",
      account: true,
      rows: accountRows,
    }, ...parentSeries];
    const compact = chart.clientWidth > 0 && chart.clientWidth < 520;
    const width = compact ? Math.max(320, Math.round(chart.clientWidth - 20)) : 900;
    const height = compact ? 220 : 284;
    const left = compact ? 44 : 58;
    const right = compact ? 16 : 30;
    const top = compact ? 34 : 38;
    const bottom = compact ? 34 : 42;
    const plotWidth = width - left - right;
    const plotHeight = height - top - bottom;
    const maxRate = Math.max(
      ...series.flatMap((item) => item.rows.map((row) => number(row.completedRate))),
      .01
    ) * 1.15;
    const xFor = (index) => accountRows.length === 1
      ? left + plotWidth / 2
      : left + index / (accountRows.length - 1) * plotWidth;
    const pathFor = (rows) => rows.map((row, index) => {
      const x = xFor(index);
      const y = top + plotHeight - number(row.completedRate) / maxRate * plotHeight;
      return `${index ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(" ");
    const grid = [0, .25, .5, .75, 1].map((step) => {
      const y = top + plotHeight * step;
      return `<line class="chart-grid-line" x1="${left}" x2="${left + plotWidth}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${percent(maxRate * (1 - step), 0)}</text>`;
    }).join("");
    const labels = accountRows.map((row, index) => `<text class="chart-axis-label" x="${xFor(index)}" y="${height - 12}" text-anchor="middle">${row.month.slice(5)}月</text>`).join("");
    const lines = series.map((item) => `
      <path class="return-chart-series ${item.account ? "account" : ""}" d="${pathFor(item.rows)}"
        style="--series-color:${item.color};--series-dash:${item.dash}"
        tabindex="0" role="img" aria-label="${escapeHtml(item.label)}月度退款率折线">
        <title>${escapeHtml(item.label)}</title>
      </path>`).join("");
    const points = series.map((item, seriesIndex) => item.rows.map((row, index) => {
      const x = xFor(index);
      const y = top + plotHeight - number(row.completedRate) / maxRate * plotHeight;
      const rate = percent(row.completedRate);
      return `<g class="return-chart-point-group" tabindex="0" role="img"
        aria-label="${escapeHtml(item.label)} ${escapeHtml(row.month)} ${rate}"
        data-series-index="${seriesIndex}" data-month-index="${index}"
        data-series-label="${escapeHtml(item.label)}" data-month="${escapeHtml(row.month)}" data-rate="${rate}">
        <circle class="return-chart-point ${item.account ? "account" : ""}" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${item.account ? 3.5 : 3}" style="--series-color:${item.color}"></circle>
      </g>`;
    }).join("")).join("");
    const valueLabels = series.map((item, seriesIndex) => item.rows.map((row, index) => {
      const x = xFor(index);
      const y = top + plotHeight - number(row.completedRate) / maxRate * plotHeight;
      const lane = Math.floor(Math.max(0, seriesIndex - 1) / 2);
      let offset = item.account ? -11 : (seriesIndex % 2 ? 15 + lane * 9 : -10 - lane * 9);
      if (y + offset > top + plotHeight + 15) offset = -10 - lane * 9;
      if (y + offset < top + 9) offset = 15 + lane * 9;
      const labelY = Math.max(top + 9, Math.min(top + plotHeight + 15, y + offset));
      return `<text class="return-chart-value ${item.account ? "account" : ""}" x="${x.toFixed(1)}" y="${labelY.toFixed(1)}" text-anchor="middle" style="--series-color:${item.color}" aria-hidden="true">${percent(row.completedRate)}</text>`;
    }).join("")).join("");
    const legend = series.map((item) => `
      <span class="return-trend-active-item">
        <i class="${item.dashClass}" style="--series-color:${item.color}" aria-hidden="true"></i>
        ${escapeHtml(item.label)}
      </span>`).join("");
    chart.innerHTML = `
      <div class="return-trend-active-series" aria-label="当前显示的趋势">${legend}</div>
      <svg viewBox="0 0 ${width} ${height}" style="height:${height}px" role="img"
        aria-label="店铺月度已完成退款率趋势，已叠加 ${visibleParents.length} 个款号">
        ${grid}${lines}${points}${valueLabels}${labels}
        <line class="return-chart-hover-axis" id="returnTrendHoverAxis"
          x1="${left}" x2="${left}" y1="${top}" y2="${top + plotHeight}" hidden></line>
        <rect class="return-chart-axis-hit" id="returnTrendAxisHit"
          x="${left}" y="${top}" width="${plotWidth}" height="${plotHeight}" aria-hidden="true"></rect>
      </svg>
      <div class="return-chart-point-tooltip" id="returnTrendPointTooltip" role="tooltip" hidden></div>`;
    const tooltip = $("#returnTrendPointTooltip");
    const svg = chart.querySelector("svg");
    const axis = $("#returnTrendHoverAxis");
    const axisHit = $("#returnTrendAxisHit");
    const pointGroups = [...chart.querySelectorAll(".return-chart-point-group")];
    const clearActivePoints = () => {
      pointGroups.forEach((point) => {
        point.classList.remove("active");
        point.removeAttribute("aria-describedby");
      });
    };
    const hideTooltip = () => {
      clearActivePoints();
      axis.setAttribute("hidden", "");
      tooltip.hidden = true;
      tooltip.classList.remove("axis-mode");
    };
    const positionTooltip = (axisX, preferredTop) => {
      const maxLeft = Math.max(8, chart.clientWidth - tooltip.offsetWidth - 8);
      let tooltipLeft = axisX + 12;
      if (tooltipLeft > maxLeft) tooltipLeft = axisX - tooltip.offsetWidth - 12;
      const maxTop = Math.max(34, chart.clientHeight - tooltip.offsetHeight - 8);
      tooltip.style.left = `${Math.max(8, Math.min(maxLeft, tooltipLeft))}px`;
      tooltip.style.top = `${Math.max(34, Math.min(maxTop, preferredTop))}px`;
    };
    const showPointTooltip = (point) => {
      const chartRect = chart.getBoundingClientRect();
      const pointRect = point.getBoundingClientRect();
      clearActivePoints();
      axis.setAttribute("hidden", "");
      tooltip.classList.remove("axis-mode");
      tooltip.innerHTML = `
        <strong>${escapeHtml(point.dataset.seriesLabel)}</strong>
        <span>${escapeHtml(point.dataset.month)} · 已完成退款率</span>
        <b>${escapeHtml(point.dataset.rate)}</b>`;
      tooltip.hidden = false;
      const centerX = pointRect.left + pointRect.width / 2 - chartRect.left;
      let tooltipTop = pointRect.top - chartRect.top - tooltip.offsetHeight - 12;
      if (tooltipTop < 34) tooltipTop = pointRect.bottom - chartRect.top + 12;
      positionTooltip(centerX - 12, tooltipTop);
      point.classList.add("active");
      point.setAttribute("aria-describedby", tooltip.id);
    };
    const showAxisTooltip = (monthIndex) => {
      const chartRect = chart.getBoundingClientRect();
      const svgRect = svg.getBoundingClientRect();
      const month = accountRows[monthIndex]?.month || "";
      const x = xFor(monthIndex);
      clearActivePoints();
      pointGroups
        .filter((point) => number(point.dataset.monthIndex) === monthIndex)
        .forEach((point) => point.classList.add("active"));
      tooltip.classList.add("axis-mode");
      tooltip.innerHTML = `
        <strong>${escapeHtml(month)}</strong>
        <div class="return-chart-tooltip-values">
          ${series.map((item) => `
            <div class="return-chart-tooltip-row">
              <span><i style="--series-color:${item.color}" aria-hidden="true"></i>${escapeHtml(item.label)}</span>
              <b style="--series-color:${item.color}">${percent(item.rows[monthIndex]?.completedRate)}</b>
            </div>`).join("")}
        </div>`;
      tooltip.hidden = false;
      axis.setAttribute("x1", x.toFixed(1));
      axis.setAttribute("x2", x.toFixed(1));
      axis.removeAttribute("hidden");
      const axisX = svgRect.left - chartRect.left + x / width * svgRect.width;
      const plotTop = svgRect.top - chartRect.top + top / height * svgRect.height + 8;
      positionTooltip(axisX, plotTop);
    };
    const monthIndexForPointer = (event) => {
      const svgRect = svg.getBoundingClientRect();
      const pointerX = (event.clientX - svgRect.left) / svgRect.width * width;
      return accountRows.reduce((nearest, row, index) => (
        Math.abs(xFor(index) - pointerX) < Math.abs(xFor(nearest) - pointerX) ? index : nearest
      ), 0);
    };
    axisHit.addEventListener("pointermove", (event) => showAxisTooltip(monthIndexForPointer(event)));
    axisHit.addEventListener("pointerleave", hideTooltip);
    pointGroups.forEach((point) => {
      point.addEventListener("focus", () => showPointTooltip(point));
      point.addEventListener("blur", hideTooltip);
      point.addEventListener("keydown", (event) => {
        if (event.key === "Escape") hideTooltip();
      });
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
    if (state.group === "parent") return [...(state.data.parentRows || [])]
      .sort((a, b) => compareStyleNo(a.parentSku, b.parentSku));
    return scopedSkuRows();
  }

  function renderOverview() {
    const body = $("#returnOverviewBody");
    if (!body) return;
    const showParentTotal = state.group === "parent";
    $("#returnTotalRateHeader").hidden = !showParentTotal;
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
      ? "本月退款率用于定位当月问题，款号累计总退款率按全部完整导入月份加权计算。"
      : `${state.selectedParentSku || "未选择款号"} 的 SKU，本月退款率按 SKU 计算。`;
    if (!pageRows.length) {
      body.innerHTML = `<tr><td colspan="${showParentTotal ? 11 : 10}"><div class="empty-state"><strong>没有符合条件的退款记录</strong>调整月份、维度、风险或搜索条件后重试。</div></td></tr>`;
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
        const totalRate = number(row.totalSoldUnits) > 0
          ? `<strong>${percent(row.totalCompletedRate)}</strong><small>${integer(row.totalCompletedUnits)} / ${integer(row.totalSoldUnits)}</small>`
          : `<strong class="return-rate-missing">缺少分母</strong><small>完整导入月份无销量</small>`;
        const selected = state.group === "parent" && row.parentSku === state.selectedParentSku;
        const keyMarkup = state.group === "parent"
          ? `<button class="return-parent-button" type="button" data-return-parent="${escapeHtml(row.parentSku)}" aria-current="${selected ? "true" : "false"}" title="查看 ${escapeHtml(key)} 的款内色码分析">${escapeHtml(key)}</button>`
          : `<strong title="${escapeHtml(key)}">${escapeHtml(key)}</strong>`;
        return `<tr class="${selected ? "return-selected-row" : ""}">
          <td><div class="return-product-cell">${image}<div>${keyMarkup}<span>${escapeHtml(detail)}</span></div></div></td>
          <td><span class="return-risk ${risk.className}">${risk.label}</span></td>
          <td class="num">${integer(row.requestedUnits)}</td>
          <td class="num">${integer(row.completedUnits)}</td>
          <td class="num">${integer(row.cancelledUnits)}</td>
          <td class="num">${integer(row.pendingUnits)}</td>
          <td class="num">${integer(row.soldUnits)}</td>
          <td class="num">${percent(row.completedRate)}</td>
          ${showParentTotal ? `<td class="num"><span class="return-total-rate">${totalRate}</span></td>` : ""}
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
