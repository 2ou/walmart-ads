
    const dashboardResponse = await fetch("/api/dashboard/data");
    if (!dashboardResponse.ok) throw new Error(`加载数据失败：HTTP ${dashboardResponse.status}`);
    let DATA = await dashboardResponse.json();
    const importSourceGroups = [
      {
        label: "日常经营",
        sources: [
          { key: "ORDER", label: "每日订单", hint: "PO_Data*.xlsx" },
          { key: "ITEM_PERFORMANCE", label: "广告商品表现", hint: "ItemPerformance*.csv" },
          { key: "ITEM_KEYWORD_PERFORMANCE", label: "自动广告搜索词", hint: "ItemKeywordPerformance*.csv" },
          { key: "KEYWORD_PERFORMANCE", label: "手动广告关键词", hint: "KeywordPerformance*.csv" },
        ],
      },
      {
        label: "库存与产品",
        sources: [
          { key: "INVENTORY", label: "日常库存", hint: "date/YYYY-M-D/inventory*.xlsx" },
          { key: "PRODUCT", label: "产品资料", hint: "source/upload/*-upload.xlsx" },
          { key: "ITEM_REPORT", label: "Item Report 产品表", hint: "source/ItemReport/ItemReport_YYYYMMDD.csv" },
          { key: "PRODUCTION_ORDER", label: "生产订单（做货）", hint: "source/生产订单/*.xlsx" },
          { key: "SHIPMENT", label: "货件列表（路途库存）", hint: "source/货件/*货件*.xlsx" },
          { key: "SHIPMENT_LOGISTICS", label: "物流信息登记", hint: "source/货件/*物流信息登记*.xlsx" },
        ],
      },
      {
        label: "专项与历史",
        sources: [
          { key: "RETURN_ORDER", label: "月度退款", hint: "Return_Orders_YYYY-MM.xlsx" },
          { key: "SKU_MAPPING", label: "SKU / Item / UPC 映射", hint: "WFS上传模板.xlsx" },
          { key: "HISTORY", label: "早期销售与广告历史", hint: "PNK 每日销售数据统计.xlsx" },
        ],
      },
    ];
    const importSources = importSourceGroups.flatMap((group) => group.sources);
    const state = {
      view: "dashboard",
      periodDays: DATA.defaults.periodDays,
      customPeriodActive: false,
      customPeriodStartDate: null,
      customPeriodEndDate: null,
      campaignFilter: "all",
      campaignSearch: "",
      diagnosisMode: "recent",
      diagnosisStartDate: null,
      diagnosisEndDate: null,
      diagnosisPresetDays: null,
      thresholds: {
        targetRoas: DATA.defaults.targetRoas,
        floorRoas: DATA.defaults.floorRoas,
        minClicks: DATA.defaults.minClicks,
        minSpend: DATA.defaults.minSpend,
      },
      sales: null,
      salesLoading: false,
      salesRequestId: 0,
      salesGroup: "parent",
      salesStatus: "all",
      salesSearch: "",
      salesSort: "style",
      salesSortDirection: "asc",
      productionOrderQuery: "",
      productionOrderExpanded: new Set(),
      forecastDetailExpanded: new Set(),
      forecastFactors: [],
      parentDailySales: [],
      replenishmentDecisions: [],
      replenishmentPlan: null,
      forecastWorkbenchExpanded: new Set(),
      forecastWorkbenchSearch: "",
      forecastWorkbenchFilter: "all",
      forecastWorkbenchSort: "style",
      forecastWorkbenchDuplicateRows: 0,
      forecastSavingParents: new Set(),
      salesRankMetric: "units",
      salesPage: 0,
      salesPageSize: 50,
      inventory: null,
      inventoryLoading: false,
      inventoryTargetDays: 60,
      inventoryTrendRange: "all",
      inventoryTrendSeries: new Set(["total", "available", "transit", "reserved", "production"]),
      inventoryRisk: "all",
      inventorySearch: "",
      inventoryPage: 0,
      inventoryPageSize: 50,
      inventoryByStyleExpanded: new Set(),
      inventorySearchSavedExpanded: null,
      inventoryByStyleSort: { key: "parentSku", dir: "asc" },
      inventoryByStyleDetailSort: { key: "availableUnits", dir: "desc" },
      transitShipmentParentQuery: "",
      transitBucketFilter: "",
      demandForecast: null,
      demandForecastSearch: "",
      demandForecastSort: { key: "sku", dir: "asc" },
      products: null,
      productsLoading: false,
      productSearch: "",
      productTagFilter: "all",
      productPage: 0,
      productPageSize: 50,
      operations: null,
      operationTargets: [],
      operationsLoading: false,
      operationSearch: "",
      aiStatus: null,
      importSources: new Set(importSources.map((source) => source.key)),
      importRunning: false,
      recent: null,
      recentDays: 7,
      recentRequestId: 0,
      styleSales: null,
      adProducts: null,
      adProductsLatestDay: null,
    };

    const titles = { dashboard: "经营驾驶舱", sales: "销量分析", diagnosis: "广告诊断", sku: "SKU 机会矩阵", inventory: "库存管理", products: "产品库", operations: "运营记录", recent: "每日销售数量统计", agent: "Agent 控制室", imports: "数据导入", "profit-estimate": "毛利估算", "demand-forecast": "需求预测", "box-convert": "流水线装箱转换" };
    const decisionCopy = {
      scale: { label: "建议放量", short: "放量", reason: "ROAS 达标且已有稳定广告订单，可进入小步增量测试。" },
      reduce: { label: "建议降耗", short: "降耗", reason: "达到样本门槛后仍低于降耗 ROAS，需要降低出价或暂停低效商品。" },
      optimize: { label: "继续优化", short: "优化", reason: "已有足够数据但未达到放量目标，适合调整商品、词或出价。" },
      collect: { label: "数据不足", short: "观察", reason: "点击或花费不足，暂不做预算变更，继续积累样本。" },
    };
    let decisionConfigType = "自动广告";
    let decisionConfigCache = {};
    const inventoryRiskCopy = {
      OUT_OF_STOCK: { label: "缺货", badge: "reduce", group: "critical" },
      URGENT: { label: "7天内风险", badge: "reduce", group: "critical" },
      LOW: { label: "14天内风险", badge: "optimize", group: "warning" },
      WATCH: { label: "30天内关注", badge: "optimize", group: "warning" },
      REPLENISH: { label: "建议补货", badge: "optimize", group: "warning" },
      OVERSTOCK: { label: "库存偏高", badge: "collect", group: "neutral" },
      NO_DEMAND: { label: "近期无动销", badge: "collect", group: "neutral" },
      HEALTHY: { label: "库存健康", badge: "scale", group: "healthy" },
    };
    const confidenceCopy = { HIGH: "高", MEDIUM: "中", LOW: "低" };
    const salesStatusCopy = {
      GROWING: { label: "增长", badge: "scale" },
      DECLINING: { label: "下滑", badge: "reduce" },
      NEW: { label: "新增动销", badge: "optimize" },
      STABLE: { label: "稳定", badge: "collect" },
      NO_SALES: { label: "无销量", badge: "collect" },
    };
    const operationActionCopy = {
      NOTE: "仅备注",
      LISTING_TITLE: "Listing 标题",
      LISTING_IMAGES: "Listing 图片",
      LISTING_CONTENT: "五点 / 描述 / 属性",
      PRICE: "售价调整",
      AD_BID: "广告出价",
      AD_BUDGET: "广告预算",
      AD_KEYWORDS: "广告关键词",
      AD_STATUS: "广告启停",
      PROMOTION: "促销活动",
      INVENTORY: "库存动作",
      OTHER: "其他",
    };
    const operationSourceCopy = { MANUAL: "人工", SYSTEM: "系统", AI: "AI" };
    const operationStatusCopy = { EXECUTED: "已执行", PLANNED: "计划中", CANCELLED: "已取消" };
    const operationImpactCopy = {
      READY: { label: "完整窗口", badge: "scale" },
      COLLECTING: { label: "观察中", badge: "optimize" },
      WAITING: { label: "等待数据", badge: "collect" },
      NOT_EXECUTED: { label: "暂不追踪", badge: "collect" },
    };

    const $ = (selector) => document.querySelector(selector);
    const $$ = (selector) => [...document.querySelectorAll(selector)];
    const styleNumberCollator = new Intl.Collator("zh-CN", { numeric: true, sensitivity: "base" });
    const compareStyleNo = (left, right) => styleNumberCollator.compare(String(left ?? ""), String(right ?? ""));
    const normalizeSearchText = (value) => String(value ?? "").normalize("NFKC").toLocaleLowerCase("zh-CN").trim();
    const compactSearchText = (value) => normalizeSearchText(value).replace(/[^\p{L}\p{N}]+/gu, "");
    let lightboxReturnDrawer = null;
    function openImageLightbox(url, caption) {
      if (!url) return;
      const dialog = $("#imageLightbox");
      if (!dialog) return;
      const img = $("#imageLightboxImg");
      const cap = $("#imageLightboxCaption");
      img.src = url;
      img.alt = caption || "";
      cap.textContent = caption || "";
      // 若已有其它模态弹层（如产品详情抽屉）打开，先临时关闭，关闭图片后再恢复
      const openModal = document.querySelector("dialog[open]");
      if (openModal && openModal.id !== "imageLightbox") {
        if (openModal.id === "productDrawer") {
          lightboxReturnDrawer = { parentSku: $("#productDrawerSku").textContent };
        }
        openModal.close();
      }
      if (typeof dialog.showModal === "function") dialog.showModal();
    }
    function closeImageLightbox() {
      const dialog = $("#imageLightbox");
      if (dialog && typeof dialog.close === "function") dialog.close();
    }
    const fuzzySearchMatches = (query, ...values) => {
      const normalizedQuery = normalizeSearchText(query);
      if (!normalizedQuery) return true;
      const terms = normalizedQuery.split(/\s+/);
      const fields = values.flat(Infinity)
        .filter((value) => value !== null && value !== undefined)
        .map((value) => normalizeSearchText(value));
      // 逐字段独立匹配：每个 term 需在【某个单独字段】内命中，
      // 禁止跨字段拼接（避免 "607" + "8" 被粘连误判为 "6078"）。
      return terms.every((term) => {
        const compactTerm = compactSearchText(term);
        return fields.some((field) => field.includes(term) || compactSearchText(field).includes(compactTerm));
      });
    };
    window.fuzzySearchMatches = fuzzySearchMatches;
    const number = (value) => Number.isFinite(Number(value)) ? Number(value) : 0;
    const sum = (rows, key) => rows.reduce((total, row) => total + number(row[key]), 0);
    const money = (value, digits = 0) => `$${number(value).toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
    const integer = (value) => Math.round(number(value)).toLocaleString("en-US");
    const shortModelName = (raw) => String(raw || "")
      .replace(/STATSFORECAST_AUTO_ETS/g, "AutoETS")
      .replace(/BASELINE_RECENT_28/g, "基线28")
      .replace(/BASELINE_SERVICE_FALLBACK/g, "兜底基线");
    const percent = (value, digits = 1) => `${(number(value) * 100).toFixed(digits)}%`;
    const profitText = (profit, margin) => (profit == null || margin == null)
      ? "—"
      : `¥${Number(profit).toFixed(2)} / ${(Number(margin) * 100).toFixed(2)}%`;
    const refundSourceText = (source) => source === "HISTORY" ? "系统历史" : source === "MANUAL" ? "手动" : "默认 0";
    const ratio = (value) => `${number(value).toFixed(2)}x`;
    const units = (value) => `${integer(value)} 件`;
    const daysText = (value) => value === null || value === undefined ? "无销量" : `${number(value).toFixed(1)} 天`;
    const isoDate = (value) => new Date(`${value}T00:00:00`);
    const dateText = (value) => new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric" }).format(isoDate(value));
    const fullDateText = (value) => new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric" }).format(isoDate(value));
    const latest = isoDate(DATA.meta.latestDate);
    const addDays = (source, days) => { const result = new Date(source); result.setDate(result.getDate() + days); return result; };
    const toIso = (value) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
    const inclusiveDays = (startDate, endDate) => Math.round((isoDate(endDate) - isoDate(startDate)) / 86400000) + 1;
    const shortDate = (value) => {
      const [, month, day] = String(value).split("-");
      return `${number(month)}/${number(day)}`;
    };

    function activePeriodRange(offset = 0) {
      const end = state.customPeriodActive && state.customPeriodEndDate
        ? addDays(isoDate(state.customPeriodEndDate), -offset)
        : addDays(latest, -offset);
      const start = state.customPeriodActive && state.customPeriodStartDate
        ? addDays(isoDate(state.customPeriodStartDate), -offset)
        : addDays(end, -(state.periodDays - 1));
      return { start, end, startDate: toIso(start), endDate: toIso(end) };
    }

    function analysisPeriodLabel() {
      const range = activePeriodRange();
      return `${range.startDate} 至 ${range.endDate}`;
    }

    function analysisQuery(extra = {}) {
      const params = new URLSearchParams({ days: String(state.periodDays), ...extra });
      if (state.customPeriodActive) {
        params.set("startDate", state.customPeriodStartDate);
        params.set("endDate", state.customPeriodEndDate);
      }
      return params.toString();
    }

    function inWindow(row, days, offset = 0) {
      const range = activePeriodRange(offset);
      const end = state.customPeriodActive ? range.end : addDays(latest, -offset);
      const start = state.customPeriodActive ? range.start : addDays(end, -(days - 1));
      const current = isoDate(row.date);
      return current >= start && current <= end;
    }

    function aggregate(rows, days, offset = 0) {
      const chosen = rows.filter((row) => inWindow(row, days, offset));
      const result = {};
      ["orders", "revenue", "impressions", "clicks", "spend", "adOrders", "naturalOrders", "adSales"].forEach((key) => result[key] = sum(chosen, key));
      result.roas = result.spend ? result.adSales / result.spend : 0;
      result.acos = result.adSales ? result.spend / result.adSales : 0;
      result.ctr = result.impressions ? result.clicks / result.impressions : 0;
      result.cpc = result.clicks ? result.spend / result.clicks : 0;
      result.cvr = result.clicks ? result.adOrders / result.clicks : 0;
      result.adShare = result.orders ? result.adOrders / result.orders : 0;
      return result;
    }

    function groupAggregateRows(rows, groupKey) {
      const groups = new Map();
      rows.forEach((row) => {
        const key = row[groupKey];
        if (!groups.has(key)) groups.set(key, { [groupKey]: key, campaignId: row.campaignId, type: row.type, sku: row.sku });
        const item = groups.get(key);
        ["orders", "revenue", "impressions", "clicks", "spend", "adOrders", "naturalOrders", "adSales"].forEach((field) => item[field] = number(item[field]) + number(row[field]));
        if (row.avgBid) { item.bidWeighted = number(item.bidWeighted) + number(row.avgBid) * Math.max(number(row.clicks), 1); item.bidWeight = number(item.bidWeight) + Math.max(number(row.clicks), 1); }
      });
      return [...groups.values()].map((item) => ({
        ...item,
        roas: item.spend ? item.adSales / item.spend : 0,
        acos: item.adSales ? item.spend / item.adSales : 0,
        cpc: item.clicks ? item.spend / item.clicks : 0,
        ctr: item.impressions ? item.clicks / item.impressions : 0,
        adShare: item.orders ? item.adOrders / item.orders : 0,
        avgBid: item.bidWeight ? item.bidWeighted / item.bidWeight : 0,
      }));
    }

    function groupAggregate(rows, groupKey, days) {
      return groupAggregateRows(rows.filter((row) => inWindow(row, days)), groupKey);
    }

    function groupAggregateRange(rows, groupKey, startDate, endDate) {
      return groupAggregateRows(rows.filter((row) => row.date >= startDate && row.date <= endDate), groupKey);
    }

    function decide(item) {
      const enough = number(item.clicks) >= state.thresholds.minClicks && number(item.spend) >= state.thresholds.minSpend;
      if (!enough) return "collect";
      if (number(item.roas) >= state.thresholds.targetRoas && number(item.adOrders) >= 3) return "scale";
      if (number(item.roas) < state.thresholds.floorRoas || number(item.adOrders) === 0) return "reduce";
      return "optimize";
    }

    function delta(current, previous, inverse = false) {
      if (!previous) return { text: "无可比基期", cls: "flat", arrow: "" };
      const value = (current - previous) / Math.abs(previous);
      const good = inverse ? value <= 0 : value >= 0;
      return { text: `${Math.abs(value * 100).toFixed(1)}%`, cls: Math.abs(value) < .005 ? "flat" : good ? "up" : "down", arrow: Math.abs(value) < .005 ? "" : value > 0 ? "↑" : "↓" };
    }

    function metricCard(label, value, change, note, icon, cost = false) {
      return `<article class="metric-card${cost ? " cost" : ""}"><div class="metric-label"><span>${label}</span><span class="metric-icon">${icon}</span></div><div class="metric-value">${value}</div><div class="metric-foot"><span class="change ${change.cls}">${change.arrow} ${change.text}</span><span>${note}</span></div></article>`;
    }

    const icons = {
      revenue: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M12 2v20M17 6.5c0-1.4-2-2.5-4.5-2.5S8 5.1 8 6.5 10 9 12.5 9 17 10.1 17 11.5 15 14 12.5 14 8 15.1 8 16.5 10 19 12.5 19s4.5-1.1 4.5-2.5"/></svg>`,
      orders: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M6 3h12l2 5-2 13H6L4 8zM4 8h16M9 12v5M15 12v5"/></svg>`,
      spend: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 10h18M16 15h2"/></svg>`,
      roas: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 16l5-5 4 3 7-8"/><path d="M15 6h5v5"/></svg>`,
    };

    function renderMetrics() {
      const current = aggregate(DATA.daily, state.periodDays);
      const previous = aggregate(DATA.daily, state.periodDays, state.periodDays);
      $("#metricGrid").innerHTML = [
        metricCard("总销售额", money(current.revenue, 2), delta(current.revenue, previous.revenue), "较前一周期", icons.revenue),
        metricCard("销售件数", integer(current.orders), delta(current.orders, previous.orders), "较前一周期", icons.orders),
        metricCard("广告费", money(current.spend, 2), delta(current.spend, previous.spend, true), `CPC ${money(current.cpc, 2)}`, icons.spend, true),
        metricCard("广告 ROAS", ratio(current.roas), delta(current.roas, previous.roas), `ACOS ${percent(current.acos)}`, icons.roas),
      ].join("");
      const roasState = current.roas >= state.thresholds.targetRoas ? "整体 ROAS 达到当前放量阈值" : current.roas < state.thresholds.floorRoas ? "整体 ROAS 低于当前降耗阈值" : "整体 ROAS 位于优化区间";
      $("#statusHeadline").textContent = roasState;
      $("#statusDetail").textContent = `广告归因销售 ${money(current.adSales, 2)}，广告订单 ${integer(current.adOrders)}，自然订单 ${integer(current.naturalOrders)}。`;
      $("#statusRange").textContent = analysisPeriodLabel();
      renderTrend();
      renderOrderMix(current);
    }

    function renderTrend() {
      // 折线图最少展示 7 天：以选中周期结束日为终点，向前取 max(7, 选中跨度) 天；KPI 仍按真实选中周期聚合
      const trendRange = activePeriodRange(0);
      const trendEnd = trendRange.end;
      const selectedSpan = state.customPeriodActive ? inclusiveDays(trendRange.start, trendRange.end) : state.periodDays;
      const trendDays = Math.max(7, selectedSpan);
      const trendStart = addDays(trendEnd, -(trendDays - 1));
      const rows = DATA.daily.filter((row) => {
        const d = isoDate(row.date);
        return d >= trendStart && d <= trendEnd;
      });
      if (!rows.length) { $("#trendChart").innerHTML = `<div class="empty-state"><strong>没有趋势数据</strong>请检查分析周期。</div>`; return; }
      const trendBoxCs = getComputedStyle($("#trendChart"));
      const trendInnerWidth = $("#trendChart").clientWidth - parseFloat(trendBoxCs.paddingLeft) - parseFloat(trendBoxCs.paddingRight);
      const width = Math.max(360, Math.round(trendInnerWidth)), height = 260, left = 54, right = 104, top = 18, bottom = 34;
      const plotW = width - left - right, plotH = height - top - bottom;
      const maxSales = Math.max(...rows.map((row) => number(row.revenue)), 1) * 1.12;
      const maxSpend = Math.max(...rows.map((row) => number(row.spend)), 1) * 1.12;
      const maxOrders = Math.max(...rows.map((row) => number(row.orders)), 1) * 1.12;
      const xFor = (index) => left + (rows.length === 1 ? plotW / 2 : index * plotW / (rows.length - 1));
      const points = (key, max) => rows.map((row, index) => ({ x: xFor(index), y: top + plotH - number(row[key]) / max * plotH, row }));
      const sales = points("revenue", maxSales), spend = points("spend", maxSpend), orders = points("orders", maxOrders);
      const path = (items) => items.map((point, index) => `${index ? "L" : "M"}${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(" ");
      const area = `${path(sales)} L${sales.at(-1).x.toFixed(1)},${top + plotH} L${sales[0].x.toFixed(1)},${top + plotH} Z`;
      const labelEvery = Math.max(1, Math.ceil(rows.length / 7));
      const grid = [0, .25, .5, .75, 1].map((t) => {
        const y = top + plotH * t;
        return `<line class="chart-grid-line" x1="${left}" x2="${left + plotW}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${money(maxSales * (1 - t))}</text><text class="chart-axis-label" x="${left + plotW + 10}" y="${y + 3}" text-anchor="start">${money(maxSpend * (1 - t))}</text><text class="chart-axis-orders" x="${left + plotW + 60}" y="${y + 3}" text-anchor="start">${integer(maxOrders * (1 - t))}</text>`;
      }).join("");
      const labels = rows.map((row, index) => index % labelEvery === 0 || index === rows.length - 1 ? `<text class="chart-axis-label" x="${sales[index].x}" y="${height - 9}" text-anchor="middle">${dateText(row.date)}</text>` : "").join("");
      const dots = rows.map((row, index) => `<circle class="chart-point" cx="${sales[index].x}" cy="${sales[index].y}" r="3.6" fill="var(--walmart-blue)"/><circle class="chart-point" cx="${spend[index].x}" cy="${spend[index].y}" r="3.2" fill="var(--spark-yellow)"/><circle class="chart-point" cx="${orders[index].x}" cy="${orders[index].y}" r="3" fill="#2bb673"/>`).join("");
      const chart = $("#trendChart");
      chart.innerHTML = `<svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="销售额、广告投入与销量趋势图">${grid}<path class="chart-area" d="${area}"/><path class="chart-sales" d="${path(sales)}"/><path class="chart-spend" d="${path(spend)}"/><path class="chart-orders" d="${path(orders)}"/>${dots}${labels}<line class="dashboard-chart-crosshair" id="dashboardTrendCrosshair" x1="${left}" x2="${left}" y1="${top}" y2="${top + plotH}" hidden/><g id="dashboardTrendMarkers"></g><rect class="dashboard-chart-hit-target" id="dashboardTrendHitTarget" x="${left}" y="${top}" width="${plotW}" height="${plotH}" fill="transparent" tabindex="0" role="slider" aria-label="销售额、广告投入与销量日期，使用左右方向键查看" aria-valuemin="1" aria-valuemax="${rows.length}" aria-valuenow="${rows.length}"/></svg><div class="sales-tooltip" id="dashboardTrendTooltip" role="status" aria-live="polite"></div>`;

      const svg = chart.querySelector("svg");
      const hitTarget = $("#dashboardTrendHitTarget");
      const crosshair = $("#dashboardTrendCrosshair");
      const markers = $("#dashboardTrendMarkers");
      const tooltip = $("#dashboardTrendTooltip");
      let activeIndex = rows.length - 1;
      const showPoint = (index) => {
        activeIndex = Math.max(0, Math.min(rows.length - 1, index));
        const row = rows[activeIndex];
        const salesPoint = sales[activeIndex];
        const spendPoint = spend[activeIndex];
        const ordersPoint = orders[activeIndex];
        crosshair.setAttribute("x1", salesPoint.x);
        crosshair.setAttribute("x2", salesPoint.x);
        crosshair.hidden = false;
        markers.innerHTML = `<circle class="dashboard-chart-marker" cx="${salesPoint.x}" cy="${salesPoint.y}" r="5" fill="var(--walmart-blue)"/><circle class="dashboard-chart-marker" cx="${spendPoint.x}" cy="${spendPoint.y}" r="5" fill="var(--spark-yellow)"/><circle class="dashboard-chart-marker" cx="${ordersPoint.x}" cy="${ordersPoint.y}" r="5" fill="#2bb673"/>`;
        tooltip.innerHTML = `<strong>${fullDateText(row.date)}</strong><div class="sales-tooltip-row"><span>销售额</span><b>${money(row.revenue, 2)}</b></div><div class="sales-tooltip-row"><span>广告投入</span><b>${money(row.spend, 2)}</b></div><div class="sales-tooltip-row"><span>销量</span><b>${integer(row.orders)} 件</b></div>`;
        const svgRect = svg.getBoundingClientRect();
        const chartRect = chart.getBoundingClientRect();
        const tooltipHalf = Math.min(108, Math.max(92, svgRect.width / 4));
        const tooltipX = (svgRect.left - chartRect.left) + (salesPoint.x / width) * svgRect.width;
        tooltip.style.left = `${Math.max(tooltipHalf, Math.min(chart.clientWidth - tooltipHalf, tooltipX))}px`;
        tooltip.classList.add("show");
        hitTarget.setAttribute("aria-valuenow", String(activeIndex + 1));
        hitTarget.setAttribute("aria-valuetext", `${fullDateText(row.date)}，销售额 ${money(row.revenue, 2)}，广告投入 ${money(row.spend, 2)}，销量 ${integer(row.orders)} 件`);
      };
      const hidePoint = () => {
        crosshair.hidden = true;
        markers.innerHTML = "";
        tooltip.classList.remove("show");
      };
      const indexFromPointer = (event) => {
        const rect = svg.getBoundingClientRect();
        const pointerX = (event.clientX - rect.left) / rect.width * width;
        return sales.reduce((nearest, point, index) => (
          Math.abs(point.x - pointerX) < Math.abs(sales[nearest].x - pointerX) ? index : nearest
        ), 0);
      };
      hitTarget.addEventListener("pointermove", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerdown", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerleave", () => {
        if (document.activeElement !== hitTarget) hidePoint();
      });
      hitTarget.addEventListener("focus", () => showPoint(activeIndex));
      hitTarget.addEventListener("blur", hidePoint);
      hitTarget.addEventListener("keydown", (event) => {
        if (["ArrowLeft", "ArrowRight", "Home", "End", "Escape"].includes(event.key)) event.preventDefault();
        if (event.key === "ArrowLeft") showPoint(activeIndex - 1);
        if (event.key === "ArrowRight") showPoint(activeIndex + 1);
        if (event.key === "Home") showPoint(0);
        if (event.key === "End") showPoint(rows.length - 1);
        if (event.key === "Escape") hidePoint();
      });
    }

    function renderOrderMix(current) {
      const share = current.orders ? current.adOrders / current.orders : 0;
      $("#orderMix").innerHTML = `<div class="donut" style="--ad-share:${(share * 100).toFixed(2)}"><div class="donut-center"><div class="donut-value">${percent(share, 0)}</div><div class="donut-label">广告订单占比</div></div></div><div class="mix-legend"><div class="mix-row"><span class="legend-dot"></span><span>广告订单</span><strong>${integer(current.adOrders)}</strong></div><div class="mix-row"><span class="legend-dot" style="background:var(--steel-copy)"></span><span>自然订单</span><strong>${integer(current.naturalOrders)}</strong></div><div class="mix-row"><span class="legend-dot yellow"></span><span>总广告费</span><strong>${money(current.spend, 2)}</strong></div></div>`;
    }

    /* ===== 近期数据模块：订单 + 自动广告按天汇总 ===== */
    const recentShort = (iso) => { const p = String(iso).split("-"); return p.length === 3 ? `${p[1]}-${p[2]}` : iso; };
    const recentFull = (iso) => iso;

    function recentCard(label, value, note, icon, cost) {
      return `<article class="metric-card${cost ? " cost" : ""}"><div class="metric-label"><span>${label}</span><span class="metric-icon">${icon}</span></div><div class="metric-value">${value}</div><div class="metric-foot"><span class="change flat">—</span><span>${note}</span></div></article>`;
    }

    const recIcon = {
      revenue: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M12 2v20M17 6.5c0-1.4-2-2.5-4.5-2.5S8 5.1 8 6.5 10 9 12.5 9 17 10.1 17 11.5 15 14 12.5 14 8 15.1 8 16.5 10 19 12.5 19s4.5-1.1 4.5-2.5"/></svg>`,
      orders: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M6 3h12l2 5-2 13H6L4 8zM4 8h16M9 12v5M15 12v5"/></svg>`,
      impressions: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>`,
      clicks: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M9 3l1.5 4L15 8.5 11 10l-1.5 4L9 18l-3-5-4-1 3-3 1-4 3 1Z"/><path d="M16 14l1 3 3 1-2 2-1 3-2-2-3-1 2-2Z"/></svg>`,
      spend: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 10h18M16 15h2"/></svg>`,
      roas: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 16l5-5 4 3 7-8"/><path d="M15 6h5v5"/></svg>`,
    };

    async function loadRecent() {
      const requestId = ++state.recentRequestId;
      const days = state.recentDays;
      $("#recentStatusHeadline").textContent = "正在读取每日销售数量统计";
      $("#recentStatusDetail").textContent = `正在汇总近 ${days} 天的订单、各款销量与广告表现`;
      $("#recentMetricGrid").innerHTML = Array.from({ length: 12 }, () => `<div class="metric-card"><div class="metric-value">读取中</div></div>`).join("");
      $("#recentTrendChart").innerHTML = `<div class="empty-state"><strong>正在生成趋势</strong>请稍候。</div>`;
      $("#recentTableBody").innerHTML = emptyRow(16, "正在读取每日明细", "请稍候。");
      $("#stylesMetricGrid").innerHTML = Array.from({ length: 4 }, () => `<div class="metric-card"><div class="metric-value">读取中</div></div>`).join("");
      $("#stylesTrendChart").innerHTML = `<div class="empty-state"><strong>正在生成趋势</strong>请稍候。</div>`;
      $("#stylesRankList").innerHTML = `<div class="empty-state">读取中</div>`;
      $("#adsMetricGrid").innerHTML = Array.from({ length: 6 }, () => `<div class="metric-card"><div class="metric-value">读取中</div></div>`).join("");
      $("#adsBarList").innerHTML = `<div class="empty-state">读取中</div>`;
      $("#adsScatterChart").innerHTML = `<div class="empty-state"><strong>正在生成散点</strong>请稍候。</div>`;
      $("#adsTableBody").innerHTML = emptyRow(11, "正在读取广告产品明细", "请稍候。");
      try {
        const [dRes, sRes, aRes, aLatestRes] = await Promise.all([
          fetch(`/api/recent/daily?days=${days}`),
          fetch(`/api/recent/styles?days=${days}`),
          fetch(`/api/recent/ad-products?days=${days}`),
          fetch(`/api/recent/ad-products?days=1&latest=true`)
        ]);
        const [dP, sP, aP, aLatestP] = await Promise.all([dRes.json(), sRes.json(), aRes.json(), aLatestRes.json()]);
        if (requestId !== state.recentRequestId) return;
        if (!dP.success || !sP.success || !aP.success) throw new Error(dP.message || sP.message || aP.message || "数据返回异常");
        state.recent = dP.data;
        state.styleSales = sP.data;
        state.adProducts = aP.data;
        state.adProductsLatestDay = (aLatestP.success && aLatestP.data) ? aLatestP.data : null;
        renderRecent();
        renderStyleSales();
        renderAdProducts();
      } catch (error) {
        if (requestId !== state.recentRequestId) return;
        $("#recentStatusHeadline").textContent = "读取失败";
        $("#recentStatusDetail").textContent = error.message;
        $("#recentMetricGrid").innerHTML = "";
        $("#recentTrendChart").innerHTML = `<div class="empty-state"><strong>趋势读取失败</strong>${escapeHtml(error.message)}</div>`;
        $("#recentTableBody").innerHTML = emptyRow(16, "明细读取失败", escapeHtml(error.message));
        $("#stylesMetricGrid").innerHTML = "";
        $("#stylesTrendChart").innerHTML = `<div class="empty-state"><strong>各款销量读取失败</strong>${escapeHtml(error.message)}</div>`;
        $("#stylesRankList").innerHTML = `<div class="empty-state">明细读取失败</div>`;
        $("#adsMetricGrid").innerHTML = "";
        $("#adsBarList").innerHTML = `<div class="empty-state">读取失败</div>`;
        $("#adsScatterChart").innerHTML = `<div class="empty-state"><strong>散点读取失败</strong>${escapeHtml(error.message)}</div>`;
        $("#adsTableBody").innerHTML = emptyRow(11, "明细读取失败", escapeHtml(error.message));
      }
    }

    function renderRecent() {
      const data = state.recent;
      if (!data) return;
      const s = data.summary;
      $("#recentStatusHeadline").textContent = `近 ${data.days} 天经营概览`;
      $("#recentStatusDetail").textContent = `窗口 ${data.start} ~ ${data.end}，共 ${data.daily.length} 个有数据的日期`;
      $("#recentStatusRange").textContent = `最新业务日 ${data.end}`;
      renderRecentMetrics(s);
      renderRecentTrend(data.daily);
      renderRecentMix(s);
      renderRecentTable(data.daily);
    }

    function renderRecentMetrics(s) {
      $("#recentMetricGrid").innerHTML = [
        recentCard("总订单", `${integer(s.totalOrders)} 件`, "窗口累计", recIcon.orders),
        recentCard("总销售额", money(s.totalRevenue, 2), "窗口累计", recIcon.revenue),
        recentCard("客单价", money(s.avgOrderValue, 2), "销售额 / 订单", recIcon.revenue),
        recentCard("曝光量", integer(s.totalImpressions), "窗口累计", recIcon.impressions),
        recentCard("点击量", integer(s.totalClicks), "窗口累计", recIcon.clicks),
        recentCard("广告费", money(s.totalSpend, 2), `CPC ${money(s.cpc, 2)}`, recIcon.spend, true),
        recentCard("广告订单", integer(s.totalAdOrders), `占比 ${percent(s.adOrderRatio, 0)}`, recIcon.orders),
        recentCard("自然订单", integer(s.totalNaturalOrders), "非广告来源", recIcon.orders),
        recentCard("广告订单占比", percent(s.adOrderRatio, 0), "广告 / 总订单", recIcon.roas),
        recentCard("CTR", percent(s.ctr, 2), "点击 / 曝光", recIcon.clicks),
        recentCard("CPC", money(s.cpc, 2), "广告费 / 点击", recIcon.spend, true),
        recentCard("转化率", percent(s.conversionRate, 2), "广告订单 / 点击", recIcon.roas),
      ].join("");
    }

    function renderRecentMix(s) {
      const share = s.totalOrders ? s.totalAdOrders / s.totalOrders : 0;
      $("#recentOrderMix").innerHTML = `<div class="donut" style="--ad-share:${(share * 100).toFixed(2)}"><div class="donut-center"><div class="donut-value">${percent(share, 0)}</div><div class="donut-label">广告订单占比</div></div></div><div class="mix-legend"><div class="mix-row"><span class="legend-dot"></span><span>广告订单</span><strong>${integer(s.totalAdOrders)}</strong></div><div class="mix-row"><span class="legend-dot" style="background:var(--steel-copy)"></span><span>自然订单</span><strong>${integer(s.totalNaturalOrders)}</strong></div><div class="mix-row"><span class="legend-dot yellow"></span><span>总广告费</span><strong>${money(s.totalSpend, 2)}</strong></div></div>`;
    }

    function renderRecentTrend(rows) {
      if (!rows.length) { $("#recentTrendChart").innerHTML = `<div class="empty-state"><strong>没有趋势数据</strong>请检查时间窗口或导入订单 / 自动广告数据。</div>`; return; }
      const chartBox = $("#recentTrendChart");
      const boxCs = getComputedStyle(chartBox);
      const innerWidth = chartBox.clientWidth - parseFloat(boxCs.paddingLeft) - parseFloat(boxCs.paddingRight);
      const width = Math.max(360, Math.round(innerWidth)), height = 260, left = 54, right = 104, top = 18, bottom = 34;
      const plotW = width - left - right, plotH = height - top - bottom;
      const maxSales = Math.max(...rows.map((row) => number(row.revenue)), 1) * 1.12;
      const maxSpend = Math.max(...rows.map((row) => number(row.spend)), 1) * 1.12;
      const maxOrders = Math.max(...rows.map((row) => number(row.orders)), 1) * 1.12;
      const xFor = (index) => left + (rows.length === 1 ? plotW / 2 : index * plotW / (rows.length - 1));
      const points = (key, max) => rows.map((row, index) => ({ x: xFor(index), y: top + plotH - number(row[key]) / max * plotH, row }));
      const sales = points("revenue", maxSales), spend = points("spend", maxSpend), orders = points("orders", maxOrders);
      const path = (items) => items.map((point, index) => `${index ? "L" : "M"}${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(" ");
      const area = `${path(sales)} L${sales.at(-1).x.toFixed(1)},${top + plotH} L${sales[0].x.toFixed(1)},${top + plotH} Z`;
      const labelEvery = Math.max(1, Math.ceil(rows.length / 7));
      const grid = [0, .25, .5, .75, 1].map((t) => {
        const y = top + plotH * t;
        return `<line class="chart-grid-line" x1="${left}" x2="${left + plotW}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${money(maxSales * (1 - t))}</text><text class="chart-axis-label" x="${left + plotW + 10}" y="${y + 3}" text-anchor="start">${money(maxSpend * (1 - t))}</text><text class="chart-axis-orders" x="${left + plotW + 60}" y="${y + 3}" text-anchor="start">${integer(maxOrders * (1 - t))}</text>`;
      }).join("");
      const labels = rows.map((row, index) => index % labelEvery === 0 || index === rows.length - 1 ? `<text class="chart-axis-label" x="${sales[index].x}" y="${height - 9}" text-anchor="middle">${recentShort(row.date)}</text>` : "").join("");
      const dots = rows.map((row, index) => `<circle class="chart-point" cx="${sales[index].x}" cy="${sales[index].y}" r="3.6" fill="var(--walmart-blue)"/><circle class="chart-point" cx="${spend[index].x}" cy="${spend[index].y}" r="3.2" fill="var(--spark-yellow)"/><circle class="chart-point" cx="${orders[index].x}" cy="${orders[index].y}" r="3" fill="#2bb673"/>`).join("");
      chartBox.innerHTML = `<svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="销售额、广告投入与订单趋势图">${grid}<path class="chart-area" d="${area}"/><path class="chart-sales" d="${path(sales)}"/><path class="chart-spend" d="${path(spend)}"/><path class="chart-orders" d="${path(orders)}"/>${dots}${labels}<line class="dashboard-chart-crosshair" id="recentTrendCrosshair" x1="${left}" x2="${left}" y1="${top}" y2="${top + plotH}" hidden/><g id="recentTrendMarkers"></g><rect class="dashboard-chart-hit-target" id="recentTrendHitTarget" x="${left}" y="${top}" width="${plotW}" height="${plotH}" fill="transparent" tabindex="0" role="slider" aria-label="销售额、广告投入与订单日期，使用左右方向键查看" aria-valuemin="1" aria-valuemax="${rows.length}" aria-valuenow="${rows.length}"/></svg><div class="sales-tooltip" id="recentTrendTooltip" role="status" aria-live="polite"></div>`;

      const svg = chartBox.querySelector("svg");
      const hitTarget = $("#recentTrendHitTarget");
      const crosshair = $("#recentTrendCrosshair");
      const markers = $("#recentTrendMarkers");
      const tooltip = $("#recentTrendTooltip");
      let activeIndex = rows.length - 1;
      const showPoint = (index) => {
        activeIndex = Math.max(0, Math.min(rows.length - 1, index));
        const row = rows[activeIndex];
        const salesPoint = sales[activeIndex], spendPoint = spend[activeIndex], ordersPoint = orders[activeIndex];
        crosshair.setAttribute("x1", salesPoint.x);
        crosshair.setAttribute("x2", salesPoint.x);
        crosshair.hidden = false;
        markers.innerHTML = `<circle class="dashboard-chart-marker" cx="${salesPoint.x}" cy="${salesPoint.y}" r="5" fill="var(--walmart-blue)"/><circle class="dashboard-chart-marker" cx="${spendPoint.x}" cy="${spendPoint.y}" r="5" fill="var(--spark-yellow)"/><circle class="dashboard-chart-marker" cx="${ordersPoint.x}" cy="${ordersPoint.y}" r="5" fill="#2bb673"/>`;
        tooltip.innerHTML = `<strong>${recentFull(row.date)}</strong><div class="sales-tooltip-row"><span>销售额</span><b>${money(row.revenue, 2)}</b></div><div class="sales-tooltip-row"><span>广告投入</span><b>${money(row.spend, 2)}</b></div><div class="sales-tooltip-row"><span>订单</span><b>${integer(row.orders)} 件</b></div>`;
        const svgRect = svg.getBoundingClientRect();
        const chartRect = chartBox.getBoundingClientRect();
        const tooltipHalf = Math.min(108, Math.max(92, svgRect.width / 4));
        const tooltipX = (svgRect.left - chartRect.left) + (salesPoint.x / width) * svgRect.width;
        tooltip.style.left = `${Math.max(tooltipHalf, Math.min(chartBox.clientWidth - tooltipHalf, tooltipX))}px`;
        tooltip.classList.add("show");
        hitTarget.setAttribute("aria-valuenow", String(activeIndex + 1));
        hitTarget.setAttribute("aria-valuetext", `${recentFull(row.date)}，销售额 ${money(row.revenue, 2)}，广告投入 ${money(row.spend, 2)}，订单 ${integer(row.orders)} 件`);
      };
      const hidePoint = () => { crosshair.hidden = true; markers.innerHTML = ""; tooltip.classList.remove("show"); };
      const indexFromPointer = (event) => {
        const rect = svg.getBoundingClientRect();
        const pointerX = (event.clientX - rect.left) / rect.width * width;
        return sales.reduce((nearest, point, index) => (Math.abs(point.x - pointerX) < Math.abs(sales[nearest].x - pointerX) ? index : nearest), 0);
      };
      hitTarget.addEventListener("pointermove", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerdown", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerleave", () => { if (document.activeElement !== hitTarget) hidePoint(); });
      hitTarget.addEventListener("focus", () => showPoint(activeIndex));
      hitTarget.addEventListener("blur", hidePoint);
      hitTarget.addEventListener("keydown", (event) => {
        if (["ArrowLeft", "ArrowRight", "Home", "End", "Escape"].includes(event.key)) event.preventDefault();
        if (event.key === "ArrowLeft") showPoint(activeIndex - 1);
        if (event.key === "ArrowRight") showPoint(activeIndex + 1);
        if (event.key === "Home") showPoint(0);
        if (event.key === "End") showPoint(rows.length - 1);
        if (event.key === "Escape") hidePoint();
      });
    }

    function renderRecentTable(rows) {
      const body = $("#recentTableBody");
      if (!rows.length) { body.innerHTML = emptyRow(16, "没有每日明细", "请检查时间窗口或导入订单 / 自动广告数据。"); return; }
      const pageRows = [...rows].reverse();
      body.innerHTML = pageRows.map((row) => {
        const orders = number(row.orders) || 0, adOrders = number(row.adOrders) || 0;
        const spend = number(row.spend) || 0;
        return `<tr>
        <td>${weekdayCN(row.date)}</td>
        <td>${recentShort(row.date)}</td>
        <td class="num">${integer(row.orders)}</td>
        <td class="num">${money(row.revenue, 2)}</td>
        <td class="num">${money(row.avgOrderValue, 2)}</td>
        <td class="num">${integer(row.impressions)}</td>
        <td class="num">${integer(row.clicks)}</td>
        <td class="num">${money(row.spend, 2)}</td>
        <td class="num">${integer(row.adOrders)}</td>
        <td class="num">${percent(row.ctr, 2)}</td>
        <td class="num">${money(row.cpc, 2)}</td>
        <td class="num">${percent(row.conversionRate, 2)}</td>
        <td class="num">${percent(row.adOrderRatio, 0)}</td>
        <td class="num">${money(spend / orders, 2)}</td>
        <td class="num">${money(spend / adOrders, 2)}</td>
        <td class="num">${integer(row.naturalOrders)}</td>
      </tr>`;
      }).join("");
    }

    function renderStyleSales() {
      const data = state.styleSales;
      if (!data) return;
      const s = data;
      const days = Math.max(1, s.days);
      const head = s.ranking[0];
      $("#stylesMetricGrid").innerHTML = [
        recentCard("在售款数", integer(s.ranking.length), "窗口内有销量", recIcon.orders),
        recentCard("窗口总销量", `${integer(s.grandTotal)} 件`, "累计销量件数", recIcon.revenue),
        recentCard("日均销量", `${integer(s.grandTotal / days)} 件`, "总量 / 天数", recIcon.clicks),
        recentCard("头部款占比", percent(head ? head.share : 0, 0), head ? `Top1 ${head.style}` : "—", recIcon.roas),
      ].join("");
      renderStyleTrend(s.dates, s.topStyles, s.series);
      renderStyleRanking(s.ranking);
      renderStylePivot(s);
    }

    const STYLE_PALETTE = ["#4f9bff", "#ffc043", "#2bb673", "#b07cff", "#ff7a8a", "#27c2c2", "#ffa14a", "#7c8cff", "#9bd64a", "#ff6fae", "#5ad1e6", "#d3a15a"];

    // 原始「每日各品名销量」表的列序（与 Excel 一致）
    const STYLE_ORDER = ["PNK366066", "PNK369006", "PNK360368", "PNK36884", "PNK366074", "PNK366075", "PNK368006", "PNK363127", "PNK366076", "PNK366077", "PNK366078", "PNK366079", "PNK0079", "PNK366080", "PNK366082", "PNK366083", "PNK366084"];
    const STYLE_IMG_EXT = { PNK366066: "png", PNK369006: "jpeg", PNK360368: "jpeg", PNK36884: "jpeg", PNK366074: "jpeg", PNK366075: "jpeg", PNK368006: "jpeg", PNK363127: "png", PNK366076: "jpeg", PNK366077: "jpeg", PNK366078: "jpeg", PNK366079: "jpeg", PNK0079: "jpeg", PNK366080: "jpeg", PNK366082: "png", PNK366083: "png", PNK366084: "jpeg" };
    const styleImgUrl = (st) => `/img/styles/${st}.${(STYLE_IMG_EXT[st] || "jpeg")}`;
    // 产品主图：优先用产品库真实商品图（Walmart CDN），未加载/缺失时回退到本地 DISPIMG 缩图
    function stylePrimaryImg(st) {
      const list = state.products?.products || [];
      const p = list.find((x) => x.parentSku === st);
      return (p && p.imageUrl) ? p.imageUrl : styleImgUrl(st);
    }
    // 统一的缩图 <img>：CDN 失败自动回退本地；URL 转义便于放进属性
    function thumbImg(cls, st) {
      const primary = (stylePrimaryImg(st) || "").replace(/&/g, "&amp;");
      const fallback = styleImgUrl(st);
      return `<img class="${cls}" src="${primary}" alt="${escapeHtml(st)}" loading="lazy" onerror="this.onerror=null;this.src='${fallback}'"/>`;
    }
    const weekdayCN = (dateStr) => { const d = new Date(dateStr); if (isNaN(d)) return ""; return "日一二三四五六".charAt(d.getDay()).replace(/(.)/, "周$1"); };

    function renderStyleTrend(dates, topStyles, series) {
      const chartBox = $("#stylesTrendChart");
      if (!dates.length || !topStyles.length) { chartBox.innerHTML = `<div class="empty-state"><strong>没有销量数据</strong>请检查时间窗口或导入订单数据。</div>`; return; }
      const N = dates.length;
      const boxCs = getComputedStyle(chartBox);
      const innerWidth = (chartBox.clientWidth || 620) - parseFloat(boxCs.paddingLeft) - parseFloat(boxCs.paddingRight);
      const width = Math.max(360, Math.round(innerWidth)), height = 280, left = 46, right = 16, top = 16, bottom = 30;
      const plotW = width - left - right, plotH = height - top - bottom;
      const totals = new Array(N).fill(0);
      topStyles.forEach((st) => (series[st] || []).forEach((v, i) => { totals[i] += v; }));
      const maxTotal = Math.max(...totals, 1) * 1.1;
      const xFor = (i) => left + (N === 1 ? plotW / 2 : i * plotW / (N - 1));
      const yFor = (v) => top + plotH - (v / maxTotal) * plotH;
      const lower = new Array(N).fill(0);
      let areasSvg = "";
      topStyles.forEach((st, si) => {
        const color = STYLE_PALETTE[si % STYLE_PALETTE.length];
        const upper = new Array(N);
        for (let i = 0; i < N; i++) upper[i] = lower[i] + ((series[st] || [])[i] || 0);
        let d = "";
        for (let i = 0; i < N; i++) d += (i ? "L" : "M") + xFor(i).toFixed(1) + "," + yFor(upper[i]).toFixed(1);
        for (let i = N - 1; i >= 0; i--) d += "L" + xFor(i).toFixed(1) + "," + yFor(lower[i]).toFixed(1);
        d += "Z";
        areasSvg += `<path d="${d}" fill="${color}" fill-opacity="0.78" stroke="${color}" stroke-width="1" stroke-opacity="0.85"/>`;
        for (let i = 0; i < N; i++) lower[i] = upper[i];
      });
      const grid = [0, 0.5, 1].map((t) => {
        const y = top + plotH * t;
        return `<line class="chart-grid-line" x1="${left}" x2="${left + plotW}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${integer(maxTotal * (1 - t))}</text>`;
      }).join("");
      const labelEvery = Math.max(1, Math.ceil(N / 7));
      const xlabels = dates.map((dd, i) => (i % labelEvery === 0 || i === N - 1) ? `<text class="chart-axis-label" x="${xFor(i)}" y="${height - 9}" text-anchor="middle">${recentShort(dd)}</text>` : "").join("");
      chartBox.innerHTML = `<svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="各款每日销量堆叠面积图">${grid}${areasSvg}${xlabels}</svg>`;
      $("#stylesTrendLegend").innerHTML = topStyles.map((st, si) => `<span class="legend-item"><span class="legend-dot" style="background:${STYLE_PALETTE[si % STYLE_PALETTE.length]}"></span>${escapeHtml(st)}</span>`).join("");
    }

    function renderStyleRanking(ranking) {
      const box = $("#stylesRankList");
      if (!ranking.length) { box.innerHTML = `<div class="empty-state"><strong>没有销量数据</strong>请检查时间窗口或导入订单数据。</div>`; return; }
      const max = ranking[0].totalQty || 1;
      box.innerHTML = ranking.slice(0, 20).map((r) => `<div class="rank-row"><div class="rank-head">${thumbImg("rank-thumb", r.style)}<span class="rank-name">${escapeHtml(r.style)}</span><span class="rank-meta">${integer(r.totalQty)} 件 · ${percent(r.share, 1)}</span></div><div class="rank-bar-track"><div class="rank-bar-fill" style="width:${(r.totalQty / max * 100).toFixed(1)}%"></div></div></div>`).join("");
    }

    function renderStylePivot(s) {
      const head = $("#stylesPivotHead");
      const body = $("#stylesPivotBody");
      const foot = $("#stylesPivotFoot");
      if (!s.dates.length || !s.series || !Object.keys(s.series).length) {
        head.innerHTML = ""; body.innerHTML = emptyRow(2, "没有各款销量数据", "请检查时间窗口或导入订单数据。"); foot.innerHTML = ""; return;
      }
      const styles = STYLE_ORDER.filter((st) => s.series[st]);
      const N = s.dates.length;
      head.innerHTML = `<th class="pivot-corner">日期</th>` + styles.map((st) => `<th class="pivot-col-head"><div class="pivot-col-inner">${thumbImg("pivot-thumb", st)}<span>${escapeHtml(st)}</span></div></th>`).join("") + `<th class="pivot-total-head num">合计</th>`;
      const colTotals = styles.map(() => 0);
      let grand = 0;
      body.innerHTML = s.dates.map((d, i) => {
        let rowTotal = 0;
        const cells = styles.map((st, k) => {
          const v = (s.series[st] || [])[i] || 0;
          rowTotal += v; colTotals[k] += v;
          return `<td class="num">${v ? integer(v) : integer(0)}</td>`;
        }).join("");
        grand += rowTotal;
        return `<tr><td class="pivot-row-head">${recentShort(d)}</td>${cells}<td class="num pivot-total">${integer(rowTotal)}</td></tr>`;
      }).join("");
      foot.innerHTML = `<td class="pivot-row-head">合计</td>` + colTotals.map((v) => `<td class="num pivot-total">${integer(v)}</td>`).join("") + `<td class="num pivot-total">${integer(grand)}</td>`;
    }

    function renderAdProducts() {
      const data = state.adProducts;
      if (!data) return;
      const s = data.summary;
      $("#adsMetricGrid").innerHTML = [
        recentCard("广告费", money(s.totalSpend, 2), "窗口累计", recIcon.spend, true),
        recentCard("归因销售", money(s.totalSales, 2), "广告带来 GMV", recIcon.revenue),
        recentCard("RoAS", ratio(s.roas), "销售 / 广告费", recIcon.roas),
        recentCard("ACOS", percent(s.acos, 1), "广告费 / 销售", recIcon.spend, true),
        recentCard("广告订单", integer(s.totalOrders), "归因订单", recIcon.orders),
        recentCard("曝光量", integer(s.totalImpressions), "窗口累计", recIcon.impressions),
      ].join("");
      // 广告产品明细表固定显示「最新一天」；汇总卡片/条形/散点仍用窗口聚合
      const detail = (state.adProductsLatestDay && state.adProductsLatestDay.products) ? state.adProductsLatestDay : data;
      const detailDay = (state.adProductsLatestDay && state.adProductsLatestDay.latest) ? state.adProductsLatestDay.end : null;
      const sub = $("#adsDetailSubtitle");
      if (sub) sub.textContent = detailDay ? `最新一天（${detailDay}）· 按广告费降序` : "按广告费降序，父 SKU 级聚合";
      renderAdBars(data.products);
      renderAdScatter(data.products);
      renderAdTable(detail.products);
    }

    function renderAdBars(products) {
      const box = $("#adsBarList");
      if (!products.length) { box.innerHTML = `<div class="empty-state"><strong>没有广告数据</strong>请检查时间窗口或导入广告数据。</div>`; return; }
      const max = Math.max(...products.map((p) => p.sales), 1);
      box.innerHTML = products.slice(0, 15).map((p) => `<div class="rank-row"><div class="rank-head"><span class="rank-name">${escapeHtml(p.style)}</span><span class="rank-meta">${money(p.sales, 0)} · RoAS ${ratio(p.roas)}</span></div><div class="rank-bar-track"><div class="rank-bar-fill sales" style="width:${(p.sales / max * 100).toFixed(1)}%"></div></div></div>`).join("");
    }

    function renderAdScatter(products) {
      const chartBox = $("#adsScatterChart");
      const pts = products.filter((p) => p.spend > 0 && p.sales > 0);
      if (!pts.length) { chartBox.innerHTML = `<div class="empty-state"><strong>没有广告数据</strong>请检查时间窗口或导入广告数据。</div>`; return; }
      const boxCs = getComputedStyle(chartBox);
      const innerWidth = (chartBox.clientWidth || 620) - parseFloat(boxCs.paddingLeft) - parseFloat(boxCs.paddingRight);
      const width = Math.max(360, Math.round(innerWidth)), height = 300, left = 54, right = 16, top = 16, bottom = 36;
      const plotW = width - left - right, plotH = height - top - bottom;
      const maxSpend = Math.max(...pts.map((p) => p.spend), 1) * 1.12;
      const maxSales = Math.max(...pts.map((p) => p.sales), 1) * 1.12;
      const maxOrders = Math.max(...pts.map((p) => p.orders), 1);
      const xFor = (v) => left + (v / maxSpend) * plotW;
      const yFor = (v) => top + plotH - (v / maxSales) * plotH;
      const rFor = (o) => 4 + Math.sqrt(o / maxOrders) * 12;
      const hgrid = [0, 0.5, 1].map((t) => {
        const y = top + plotH * t;
        return `<line class="chart-grid-line" x1="${left}" x2="${left + plotW}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${money(maxSales * (1 - t), 0)}</text>`;
      }).join("");
      const vgrid = [0, 0.5, 1].map((t) => {
        const x = left + plotW * t;
        return `<line class="chart-grid-line" x1="${x}" x2="${x}" y1="${top}" y2="${top + plotH}"/><text class="chart-axis-label" x="${x}" y="${height - 16}" text-anchor="middle">${money(maxSpend * t, 0)}</text>`;
      }).join("");
      const circles = pts.map((p) => `<circle cx="${xFor(p.spend).toFixed(1)}" cy="${yFor(p.sales).toFixed(1)}" r="${rFor(p.orders).toFixed(1)}" fill="var(--spark-yellow)" fill-opacity="0.55" stroke="var(--spark-yellow)" stroke-opacity="0.9"><title>${escapeHtml(p.style)}：广告费 ${money(p.spend, 0)} / 归因销售 ${money(p.sales, 0)} / RoAS ${ratio(p.roas)}</title></circle>`).join("");
      chartBox.innerHTML = `<svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="广告费与归因销售散点图">${hgrid}${vgrid}${circles}<text class="chart-axis-label" x="${left + plotW / 2}" y="${height - 3}" text-anchor="middle">广告费 (USD)</text></svg>`;
    }

    function renderAdTable(products) {
      const body = $("#adsTableBody");
      if (!products.length) { body.innerHTML = emptyRow(11, "没有广告产品明细", "请检查时间窗口或导入广告数据。"); return; }
      body.innerHTML = products.map((p) => `<tr>
        <td class="ad-product-cell">${thumbImg("ad-thumb", p.style)}<span class="ad-style">${escapeHtml(p.style)}</span></td>
        <td class="num">${money(p.spend, 2)}</td>
        <td class="num">${money(p.sales, 2)}</td>
        <td class="num">${ratio(p.roas)}</td>
        <td class="num">${percent(p.acos, 1)}</td>
        <td class="num">${integer(p.orders)}</td>
        <td class="num">${integer(p.clicks)}</td>
        <td class="num">${integer(p.impressions)}</td>
        <td class="num">${percent(p.ctr, 2)}</td>
        <td class="num">${money(p.cpc, 2)}</td>
        <td class="num">${percent(p.conversionRate, 2)}</td>
      </tr>`).join("");
    }

    function renderSkuSummary() {
      const rows = groupAggregate(DATA.skuDaily, "sku", state.periodDays).sort((a, b) => b.revenue - a.revenue);
      const maxRevenue = Math.max(...rows.map((row) => row.revenue), 1);
      $("#topSkuBody").innerHTML = rows.slice(0, 8).map((row) => { const decision = decide(row); return `<tr><td><span class="sku-name">${escapeHtml(row.sku)}</span><div class="rank-bar"><span style="width:${row.revenue / maxRevenue * 100}%"></span></div></td><td class="num">${money(row.revenue, 2)}</td><td class="num">${integer(row.orders)}</td><td class="num">${money(row.spend, 2)}</td><td class="num">${ratio(row.roas)}</td><td class="num">${percent(row.adShare)}</td><td><span class="status-badge ${decision}">${decisionCopy[decision].label}</span></td></tr>`; }).join("") || emptyRow(7, "没有 SKU 数据", "请检查当前分析周期。");
      $("#skuCount").textContent = integer(rows.filter((row) => row.orders || row.spend).length);
    }

    function applyDecisionConfigToForm() {
      const cfg = decisionConfigCache[decisionConfigType] || { targetRoas: 3, floorRoas: 1.5, minClicks: 20, minSpend: 10 };
      $("#targetRoas").value = cfg.targetRoas;
      $("#floorRoas").value = cfg.floorRoas;
      $("#minClicks").value = cfg.minClicks;
      $("#minSpend").value = cfg.minSpend;
      $$('[data-decision-type]').forEach((button) => {
        button.classList.toggle("active", button.dataset.decisionType === decisionConfigType);
      });
    }

    async function loadDecisionConfig() {
      try {
        const response = await fetch("/api/decision/config");
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        (payload.data || []).forEach((cfg) => { decisionConfigCache[cfg.campaignType] = cfg; });
      } catch (error) {
        showToast("无法读取决策阈值配置，使用默认值。");
      }
      applyDecisionConfigToForm();
    }

    async function saveDecisionConfig() {
      const cfg = {
        campaignType: decisionConfigType,
        targetRoas: number($("#targetRoas").value),
        floorRoas: number($("#floorRoas").value),
        minClicks: number($("#minClicks").value),
        minSpend: number($("#minSpend").value),
      };
      try {
        const response = await fetch("/api/decision/config", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(cfg),
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        decisionConfigCache[decisionConfigType] = payload.data;
        state.thresholds.targetRoas = cfg.targetRoas;
        state.thresholds.floorRoas = cfg.floorRoas;
        state.thresholds.minClicks = cfg.minClicks;
        state.thresholds.minSpend = cfg.minSpend;
      } catch (error) {
        showToast("保存失败，请稍后重试。");
        return;
      }
      renderAll();
      showToast(`${decisionConfigType} 决策阈值已保存并即时生效。`);
    }

    function diagnosisRange() {
      const followsCustomRange = state.diagnosisMode === "recent"
        && !state.diagnosisPresetDays
        && state.customPeriodActive;
      const fallbackEnd = followsCustomRange ? state.customPeriodEndDate : DATA.meta.latestDate;
      const recentDays = state.diagnosisMode === "recent" ? (state.diagnosisPresetDays || state.periodDays) : state.periodDays;
      const fallbackStart = followsCustomRange
        ? state.customPeriodStartDate
        : toIso(addDays(latest, -(recentDays - 1)));
      const minDate = DATA.meta.firstDate || DATA.campaignDaily.map((row) => row.date).sort()[0] || fallbackStart;
      let startDate = state.diagnosisMode === "recent" ? fallbackStart : state.diagnosisStartDate || fallbackEnd;
      let endDate = state.diagnosisMode === "recent" ? fallbackEnd : state.diagnosisEndDate || fallbackEnd;
      if (state.diagnosisMode === "single") startDate = endDate;
      startDate = startDate < minDate ? minDate : startDate > fallbackEnd ? fallbackEnd : startDate;
      endDate = endDate < minDate ? minDate : endDate > fallbackEnd ? fallbackEnd : endDate;
      if (startDate > endDate) [startDate, endDate] = [endDate, startDate];
      return { startDate, endDate, minDate, maxDate: fallbackEnd };
    }

    function diagnosisCalendarDays(startDate, endDate) {
      const dates = [];
      for (let cursor = isoDate(startDate); toIso(cursor) <= endDate; cursor = addDays(cursor, 1)) dates.push(toIso(cursor));
      return dates;
    }

    function diagnosisDailyCoverage(range) {
      const totals = new Map();
      DATA.campaignDaily.filter((row) => row.date >= range.startDate && row.date <= range.endDate).forEach((row) => {
        if (!totals.has(row.date)) totals.set(row.date, { date: row.date, rows: 0, spend: 0, adSales: 0, adOrders: 0 });
        const day = totals.get(row.date);
        day.rows += 1;
        ["spend", "adSales", "adOrders"].forEach((key) => day[key] += number(row[key]));
      });
      return diagnosisCalendarDays(range.startDate, range.endDate).map((date) => ({ date, hasData: totals.has(date), ...(totals.get(date) || { rows: 0, spend: 0, adSales: 0, adOrders: 0 }) }));
    }

    function campaignRowsForDiagnosis(range) {
      return groupAggregateRange(DATA.campaignDaily, "campaign", range.startDate, range.endDate).map((row) => ({ ...row, decision: decide(row) })).sort((a, b) => {
        const rank = { reduce: 0, scale: 1, optimize: 2, collect: 3 };
        return rank[a.decision] - rank[b.decision] || b.spend - a.spend;
      });
    }

    function renderDiagnosisRangeControls(range, coverage) {
      const missingDates = coverage.filter((day) => !day.hasData).map((day) => day.date);
      const dataDays = coverage.length - missingDates.length;
      const controls = $("#diagnosisRangeControls");
      const mode = state.diagnosisMode;
      const modeButtons = [["single", "单日"], ["recent", "近期"], ["custom", "自定义"]].map(([value, label]) => `<button class="diagnosis-mode-button ${mode === value ? "active" : ""}" type="button" data-diagnosis-mode="${value}" aria-pressed="${mode === value}">${label}</button>`).join("");
      const recentDays = state.diagnosisPresetDays || state.periodDays;
      const followingLabel = state.customPeriodActive
        ? `跟随顶部范围：${shortDate(state.customPeriodStartDate)} 至 ${shortDate(state.customPeriodEndDate)}`
        : state.periodDays === 1 ? "跟随顶部周期：最新业务日" : `跟随顶部周期：${state.periodDays} 天`;
      const recentControl = `<div class="diagnosis-range-content"><div class="diagnosis-period-chips" role="group" aria-label="近期诊断天数">${[1, 7, 14, 30].map((days) => `<button class="diagnosis-range-button ${recentDays === days ? "active" : ""}" type="button" data-diagnosis-recent="${days}">${days === 1 ? "最新日" : `近 ${days} 天`}</button>`).join("")}</div><span class="diagnosis-mode-note">${state.diagnosisPresetDays ? (recentDays === 1 ? "当前固定最新业务日" : `当前固定近 ${recentDays} 天`) : followingLabel}</span><button class="diagnosis-follow-button" id="followDiagnosisPeriod" type="button">跟随顶部</button></div>`;
      const singleControl = `<div class="diagnosis-range-content"><div class="diagnosis-single-picker"><button class="diagnosis-day-step" type="button" data-diagnosis-day-shift="-1" aria-label="上一天" ${range.endDate <= range.minDate ? "disabled" : ""}>‹</button><label class="diagnosis-single-date" for="diagnosisSingleDate">诊断日<input id="diagnosisSingleDate" type="date" min="${range.minDate}" max="${range.maxDate}" value="${range.endDate}"></label><button class="diagnosis-day-step" type="button" data-diagnosis-day-shift="1" aria-label="下一天" ${range.endDate >= range.maxDate ? "disabled" : ""}>›</button></div><span class="diagnosis-mode-note">按所选当天的数据诊断，不再需要设置两个日期。</span></div>`;
      const customControl = `<div class="diagnosis-range-content"><div class="diagnosis-custom-fields"><label class="diagnosis-date-field" for="diagnosisStartDate">开始日期<input id="diagnosisStartDate" type="date" min="${range.minDate}" max="${range.maxDate}" value="${range.startDate}"></label><span class="diagnosis-date-divider" aria-hidden="true">至</span><label class="diagnosis-date-field" for="diagnosisEndDate">结束日期<input id="diagnosisEndDate" type="date" min="${range.minDate}" max="${range.maxDate}" value="${range.endDate}"></label><button class="secondary-button diagnosis-apply-button" id="applyDiagnosisRange" type="button">应用范围</button></div><span class="diagnosis-mode-note">适合复盘某次调整前后的连续表现。</span></div>`;
      controls.innerHTML = `<div class="diagnosis-mode-switch" role="group" aria-label="诊断时间选择方式">${modeButtons}</div>${mode === "single" ? singleControl : mode === "custom" ? customControl : recentControl}`;
      $$('[data-diagnosis-mode]').forEach((button) => button.addEventListener("click", () => {
        const nextMode = button.dataset.diagnosisMode;
        state.diagnosisMode = nextMode;
        state.diagnosisPresetDays = nextMode === "recent" ? null : state.diagnosisPresetDays;
        if (nextMode === "single") state.diagnosisStartDate = state.diagnosisEndDate = range.endDate;
        if (nextMode === "custom") { state.diagnosisStartDate = range.startDate; state.diagnosisEndDate = range.endDate; }
        renderDiagnosis();
      }));
      $$('[data-diagnosis-recent]').forEach((button) => button.addEventListener("click", () => {
        state.diagnosisMode = "recent";
        state.diagnosisPresetDays = number(button.dataset.diagnosisRecent);
        state.diagnosisStartDate = null;
        state.diagnosisEndDate = null;
        renderDiagnosis();
      }));
      $("#followDiagnosisPeriod")?.addEventListener("click", () => {
        state.diagnosisMode = "recent";
        state.diagnosisPresetDays = null;
        state.diagnosisStartDate = null;
        state.diagnosisEndDate = null;
        renderDiagnosis();
        showToast(`广告诊断已跟随顶部${state.customPeriodActive ? `日期范围 ${analysisPeriodLabel()}` : ` ${state.periodDays} 天周期`}。`);
      });
      $("#diagnosisSingleDate")?.addEventListener("change", (event) => {
        state.diagnosisMode = "single";
        state.diagnosisStartDate = event.target.value;
        state.diagnosisEndDate = event.target.value;
        renderDiagnosis();
      });
      $$('[data-diagnosis-day-shift]').forEach((button) => button.addEventListener("click", () => {
        const selected = toIso(addDays(isoDate(range.endDate), number(button.dataset.diagnosisDayShift)));
        state.diagnosisMode = "single";
        state.diagnosisStartDate = selected;
        state.diagnosisEndDate = selected;
        renderDiagnosis();
      }));
      $("#applyDiagnosisRange")?.addEventListener("click", () => {
        const startDate = $("#diagnosisStartDate").value;
        const endDate = $("#diagnosisEndDate").value;
        if (!startDate || !endDate) { showToast("请先选择开始和结束日期。"); return; }
        if (startDate > endDate) { showToast("开始日期不能晚于结束日期。"); return; }
        state.diagnosisMode = "custom";
        state.diagnosisStartDate = startDate;
        state.diagnosisEndDate = endDate;
        state.diagnosisPresetDays = null;
        renderDiagnosis();
      });
      const coveragePanel = $("#diagnosisDataCoverage");
      coveragePanel.classList.toggle("warning", missingDates.length > 0);
      coveragePanel.innerHTML = missingDates.length
        ? `<div><strong>已选 ${coverage.length} 个自然日，${missingDates.length} 天未导入广告数据。</strong><br>这些日期仍包含在诊断范围内，已按 0 广告费、0 归因销售、0 广告订单计入。</div><details><summary>查看未导入日期（${missingDates.length}）</summary><p class="diagnosis-missing-dates">${missingDates.map((date) => `${date}（${dateText(date)}）`).join(" · ")}</p></details>`
        : `<div><strong>已选 ${coverage.length} 个自然日，全部 ${dataDays} 天均有广告数据。</strong><br>诊断范围按自然日完整计算，不会因为某天没有广告数据而缩短。</div>`;
    }

    function diagnosisMetricCard(label, value, note, tone = "") {
      return `<article class="diagnosis-metric ${tone}"><span>${label}</span><strong>${value}</strong><small>${note}</small></article>`;
    }

    function renderDiagnosisOverview(rows, actions, calendarDays) {
      const totalSpend = sum(rows, "spend");
      const totalAdSales = sum(rows, "adSales");
      const totalAdOrders = sum(rows, "adOrders");
      const totalClicks = sum(rows, "clicks");
      const roas = totalSpend ? totalAdSales / totalSpend : 0;
      const acos = totalAdSales ? totalSpend / totalAdSales : 0;
      const roasTone = roas >= state.thresholds.targetRoas ? "good" : roas < state.thresholds.floorRoas ? "alert" : "focus";
      const actionableTone = actions.some((row) => row.decision === "reduce") ? "alert" : actions.length ? "focus" : "good";
      $("#diagnosisMetricGrid").innerHTML = [
        diagnosisMetricCard("诊断活动", integer(rows.length), `${integer(actions.length)} 个需要动作`, actionableTone),
        diagnosisMetricCard("广告费", money(totalSpend, 2), `日均 ${money(calendarDays ? totalSpend / calendarDays : 0, 2)} · CPC ${money(totalClicks ? totalSpend / totalClicks : 0, 2)}`),
        diagnosisMetricCard("归因销售", money(totalAdSales, 2), `日均 ${money(calendarDays ? totalAdSales / calendarDays : 0, 2)} · ${integer(totalAdOrders)} 个广告订单`, "focus"),
        diagnosisMetricCard("整体 ROAS", ratio(roas), `放量目标 ${ratio(state.thresholds.targetRoas)}`, roasTone),
        diagnosisMetricCard("整体 ACOS", percent(acos), `降耗线 ${ratio(state.thresholds.floorRoas)}`, roas < state.thresholds.floorRoas ? "alert" : ""),
      ].join("");
      renderCampaignEfficiencyChart(rows);
      renderDiagnosisStructure(rows);
    }

    function renderCampaignEfficiencyChart(rows) {
      const chart = $("#campaignEfficiencyChart");
      const visibleRows = rows.filter((row) => number(row.spend) || number(row.adSales) || number(row.adOrders));
      if (!visibleRows.length) {
        chart.innerHTML = `<div class="empty-state"><strong>当前周期没有广告活动数据</strong>导入 ItemPerformance 报告后，这里会按广告费和 ROAS 展示活动分布。</div>`;
        return;
      }
      const width = 860, height = 304, left = 66, right = 24, top = 22, bottom = 42;
      const plotW = width - left - right, plotH = height - top - bottom;
      const maxSpend = Math.max(...visibleRows.map((row) => number(row.spend)), 1) * 1.12;
      const maxRoas = Math.max(state.thresholds.targetRoas * 1.25, ...visibleRows.map((row) => number(row.roas)), 1) * 1.12;
      const maxOrders = Math.max(...visibleRows.map((row) => number(row.adOrders)), 1);
      const xFor = (row) => left + number(row.spend) / maxSpend * plotW;
      const yFor = (row) => top + plotH - number(row.roas) / maxRoas * plotH;
      const grid = [0, .25, .5, .75, 1].map((step) => {
        const y = top + plotH * step;
        const x = left + plotW * step;
        return `<line class="chart-grid-line" x1="${left}" x2="${left + plotW}" y1="${y}" y2="${y}"/><text class="diagnosis-axis-label" x="${left - 9}" y="${y + 3}" text-anchor="end">${ratio(maxRoas * (1 - step))}</text><text class="diagnosis-axis-label" x="${x}" y="${height - 12}" text-anchor="middle">${money(maxSpend * step, 0)}</text>`;
      }).join("");
      const targetY = top + plotH - state.thresholds.targetRoas / maxRoas * plotH;
      const targetLine = state.thresholds.targetRoas <= maxRoas
        ? `<line class="diagnosis-threshold" x1="${left}" x2="${left + plotW}" y1="${targetY}" y2="${targetY}"/><text class="diagnosis-threshold-label" x="${left + 5}" y="${targetY - 6}">放量阈值 ${ratio(state.thresholds.targetRoas)}</text>`
        : "";
      const points = visibleRows.map((row, index) => {
        const radius = 5 + Math.sqrt(number(row.adOrders) / maxOrders) * 11;
        const label = `${row.campaign}，花费 ${money(row.spend, 2)}，ROAS ${ratio(row.roas)}，${decisionCopy[row.decision].label}`;
        return `<circle class="diagnosis-point ${row.decision}" data-diagnosis-point="${index}" data-r="${radius.toFixed(1)}" cx="${xFor(row).toFixed(1)}" cy="${yFor(row).toFixed(1)}" r="${radius.toFixed(1)}" tabindex="0" role="button" aria-label="${escapeHtml(label)}"/>`;
      }).join("");
      chart.innerHTML = `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="广告活动效率分布图，横轴为广告费，纵轴为 ROAS，圆点大小为广告订单">${grid}${targetLine}<text class="diagnosis-axis-label" x="${left + plotW / 2}" y="${height - 1}" text-anchor="middle">广告费</text><text class="diagnosis-axis-label" x="14" y="${top + plotH / 2}" text-anchor="middle" transform="rotate(-90 14 ${top + plotH / 2})">ROAS</text>${points}</svg><div class="diagnosis-scatter-tooltip" id="diagnosisScatterTooltip" role="status" aria-live="polite"></div>`;
      const tooltip = $("#diagnosisScatterTooltip");
      const showPoint = (index) => {
        const row = visibleRows[index];
        const point = chart.querySelector(`[data-diagnosis-point="${index}"]`);
        tooltip.innerHTML = `<strong>${escapeHtml(row.campaign)}</strong><span class="diagnosis-tooltip-meta">${escapeHtml(row.type || "标准广告")} · ${escapeHtml(row.sku || row.campaignId || "未映射 SKU")}</span><div class="diagnosis-tooltip-row"><span>广告费</span><b>${money(row.spend, 2)}</b></div><div class="diagnosis-tooltip-row"><span>归因销售</span><b>${money(row.adSales, 2)}</b></div><div class="diagnosis-tooltip-row"><span>广告订单</span><b>${integer(row.adOrders)}</b></div><div class="diagnosis-tooltip-row"><span>ROAS</span><b>${ratio(row.roas)}</b></div>`;
        // 根据圆点位置动态放置 tooltip，使其不遮挡圆点本身
        const chartRect = chart.getBoundingClientRect();
        const pr = point.getBoundingClientRect();
        const px = pr.left + pr.width / 2 - chartRect.left;
        const py = pr.top + pr.height / 2 - chartRect.top;
        const tw = tooltip.offsetWidth, th = tooltip.offsetHeight;
        const half = tw / 2 + 6;
        const left = Math.max(half, Math.min(chart.clientWidth - half, px));
        let top = (py - th - 14 >= 0) ? py - th - 14 : py + pr.height + 12;
        top = Math.max(6, Math.min(top, chart.clientHeight - th - 6));
        tooltip.style.left = left + "px";
        tooltip.style.top = top + "px";
        tooltip.classList.add("show");
      };
      const hidePoint = () => tooltip.classList.remove("show");
      chart.querySelectorAll("[data-diagnosis-point]").forEach((point) => {
        const index = number(point.dataset.diagnosisPoint);
        const baseR = parseFloat(point.dataset.r);
        const enlarge = () => { point.setAttribute("r", (baseR + 3).toFixed(1)); point.parentNode.appendChild(point); chart.classList.add("has-hover"); };
        const restore = () => { point.setAttribute("r", baseR.toFixed(1)); chart.classList.remove("has-hover"); };
        point.addEventListener("pointerenter", () => { enlarge(); showPoint(index); });
        point.addEventListener("focus", () => { enlarge(); showPoint(index); });
        point.addEventListener("pointerleave", () => { restore(); hidePoint(); });
        point.addEventListener("blur", () => { restore(); hidePoint(); });
        point.addEventListener("click", () => showPoint(index));
        point.addEventListener("keydown", (event) => {
          if (event.key === "Enter" || event.key === " ") { event.preventDefault(); enlarge(); showPoint(index); }
          if (event.key === "Escape") { restore(); hidePoint(); }
        });
      });
    }

    function renderDiagnosisStructure(rows) {
      const totalSpend = sum(rows, "spend");
      const groups = ["reduce", "scale", "optimize", "collect"].map((decision) => {
        const items = rows.filter((row) => row.decision === decision);
        return { decision, items, spend: sum(items, "spend") };
      });
      $("#diagnosisRiskList").innerHTML = groups.map((group) => {
        const share = totalSpend ? group.spend / totalSpend : 0;
        return `<div class="diagnosis-risk-row ${group.decision}"><div class="diagnosis-risk-label"><i aria-hidden="true"></i><span>${decisionCopy[group.decision].label}</span></div><strong>${integer(group.items.length)} 个 · ${percent(share, 0)}</strong><div class="diagnosis-risk-track" aria-label="${decisionCopy[group.decision].label}占广告费 ${percent(share, 1)}"><span style="width:${Math.max(share * 100, group.items.length ? 3 : 0)}%"></span></div></div>`;
      }).join("");
      const reduce = groups.find((group) => group.decision === "reduce");
      const actionSpend = sum(rows.filter((row) => row.decision !== "collect"), "spend");
      $("#diagnosisRiskNote").textContent = totalSpend
        ? `需执行动作的活动承载 ${percent(actionSpend / totalSpend, 1)} 广告费。建议降耗活动 ${integer(reduce.items.length)} 个，占 ${percent(reduce.spend / totalSpend, 1)}。`
        : "当前没有广告费数据，导入广告活动表现后会显示诊断结构。";
    }

    function renderDiagnosis() {
      const range = diagnosisRange();
      const coverage = diagnosisDailyCoverage(range);
      const all = campaignRowsForDiagnosis(range);
      const actions = all.filter((row) => row.decision !== "collect");
      $("#actionCount").textContent = integer(actions.length);
      renderDiagnosisRangeControls(range, coverage);
      renderDiagnosisOverview(all, actions, coverage.length);
      $("#campaignSubtitle").textContent = `${range.startDate} 至 ${range.endDate}，共 ${coverage.length} 个自然日；有广告数据 ${coverage.filter((day) => day.hasData).length} 天，共 ${all.length} 个广告活动，按诊断优先级排序。`;
      const queue = [...actions].sort((a, b) => {
        const score = (row) => row.decision === "reduce" ? row.spend * Math.max(1, state.thresholds.floorRoas - row.roas) : row.adSales;
        return score(b) - score(a);
      }).slice(0, 3);
      $("#actionQueue").innerHTML = queue.map((row, index) => `<article class="queue-card"><div class="queue-top"><span class="status-badge ${row.decision}">${decisionCopy[row.decision].label}</span><span class="priority">P${index + 1}</span></div><h3>${escapeHtml(row.campaign)}</h3><p>${decisionCopy[row.decision].reason}</p><div class="queue-impact">花费 ${money(row.spend, 2)} · ROAS ${ratio(row.roas)} · ${integer(row.adOrders)} 单</div></article>`).join("") || `<div class="panel empty-state" style="grid-column:1/-1"><strong>当前没有达到动作门槛的活动</strong>可调低样本门槛或延长分析周期。</div>`;

      const query = state.campaignSearch;
      const filtered = all.filter((row) => (state.campaignFilter === "all" || row.decision === state.campaignFilter)
        && fuzzySearchMatches(query, row.campaign, row.campaignId, row.sku, row.type));
      $("#campaignBody").innerHTML = filtered.map((row) => `<tr><td><span class="campaign-name" title="${escapeHtml(row.campaign)}">${escapeHtml(row.campaign)}</span><span class="sub-id">${escapeHtml(row.sku || row.campaignId || "")}</span></td><td>${escapeHtml(row.type || "标准广告")}</td><td class="num">${money(row.spend, 2)}</td><td class="num">${money(row.adSales, 2)}</td><td class="num">${integer(row.adOrders)}</td><td class="num">${ratio(row.roas)}</td><td class="num">${money(row.cpc, 2)}</td><td><span class="status-badge ${row.decision}">${decisionCopy[row.decision].label}</span></td></tr>`).join("") || emptyRow(8, "没有匹配活动", "清除搜索或切换筛选条件。");
    }

    function renderSkuMatrix() {
      const rows = groupAggregate(DATA.skuDaily, "sku", state.periodDays).filter((row) => row.orders || row.spend).map((row) => ({ ...row, decision: decide(row) }));
      $("#skuCount").textContent = integer(rows.length);
      if (!rows.length) { $("#skuMatrix").innerHTML = `<div class="empty-state"><strong>没有 SKU 数据</strong>请检查当前分析周期。</div>`; return; }
      const maxRevenue = Math.max(...rows.map((row) => row.revenue), 1), maxOrders = Math.max(...rows.map((row) => row.orders), 1);
      const maxRoas = Math.max(state.thresholds.targetRoas * 2, ...rows.map((row) => Math.min(row.roas, state.thresholds.targetRoas * 3)), 1);
      const medianRevenue = [...rows].sort((a, b) => a.revenue - b.revenue)[Math.floor(rows.length / 2)].revenue;
      const points = rows.map((row) => {
        const x = Math.max(2, Math.min(98, row.roas / maxRoas * 100));
        const y = Math.max(3, Math.min(97, row.revenue / maxRevenue * 100));
        const size = 22 + Math.sqrt(row.orders / maxOrders) * 28;
        return `<div class="matrix-point ${row.decision}" style="left:${x}%;bottom:${y}%;width:${size}px;height:${size}px" title="${escapeHtml(row.sku)} · 销售 ${money(row.revenue, 2)} · ROAS ${ratio(row.roas)} · ${decisionCopy[row.decision].label}">${escapeHtml(row.sku.replace("PNK", ""))}</div>`;
      }).join("");
      $("#skuMatrix").innerHTML = `<div class="axis-title axis-y">销售额</div><div class="axis-title axis-x">ROAS 0 至 ${maxRoas.toFixed(1)}x</div><div class="matrix-plot"><span class="quadrant-label q1">高销售 · 高效率</span><span class="quadrant-label q2">高销售 · 待优化</span><span class="quadrant-label q3">低销售 · 低效率</span><span class="quadrant-label q4">低销售 · 有潜力</span>${points}</div>`;
      const priority = [...rows].sort((a, b) => {
        const rank = { reduce: 0, scale: 1, optimize: 2, collect: 3 };
        return rank[a.decision] - rank[b.decision] || b.spend - a.spend;
      }).slice(0, 10);
      $("#skuRanking").innerHTML = priority.map((row) => `<div class="matrix-list-row"><div><strong>${escapeHtml(row.sku)}</strong><span>${decisionCopy[row.decision].label} · ${integer(row.orders)} 单 · 花费 ${money(row.spend, 2)}</span></div><div class="mono">${ratio(row.roas)}</div></div>`).join("");
    }

    async function renderAgent() {
      let data;
      try {
        const response = await fetch(`/api/decision/analysis?periodDays=${state.periodDays}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        data = payload.data;
      } catch (error) {
        $("#runLog").innerHTML = `<div class="empty-state"><strong>无法加载决策结果</strong>后端服务异常，请稍后重试。</div>`;
        return;
      }
      const rows = data.rows || [];
      const summary = data.summary || { scale: 0, reduce: 0, optimize: 0, collect: 0 };
      $("#runSubtitle").textContent = `${data.rangeStart} 至 ${data.rangeEnd}（${data.periodDays} 天）分析范围。`;
      $("#runSummary").innerHTML = `<div class="run-stat"><strong>${summary.scale}</strong><span>建议放量</span></div><div class="run-stat"><strong>${summary.reduce}</strong><span>建议降耗</span></div><div class="run-stat"><strong>${summary.optimize}</strong><span>继续优化</span></div>`;
      const samples = rows.filter((row) => row.decision !== "collect").slice(0, 5);
      $("#runLog").innerHTML = samples.map((row, index) => `<div class="log-row"><span class="log-time">RULE-${String(index + 1).padStart(2, "0")}</span><span class="log-copy"><strong>${escapeHtml(row.sku || row.campaign)}</strong>：${decisionCopy[row.decision].reason}</span><span class="status-badge ${row.decision}">${decisionCopy[row.decision].short}</span></div>`).join("") || `<div class="empty-state"><strong>没有触发动作规则</strong>分析已完成，当前仅保留观察项。</div>`;
    }

    function salesChange(value) {
      if (value === null || value === undefined) return { text: "新增", cls: "new", arrow: "" };
      const current = number(value);
      if (Math.abs(current) < .0005) return { text: "持平", cls: "", arrow: "" };
      return {
        text: `${Math.abs(current * 100).toFixed(1)}%`,
        cls: current > 0 ? "up" : "down",
        arrow: current > 0 ? "↑" : "↓",
      };
    }

    function salesMetricCard(label, value, changeValue, note) {
      const change = salesChange(changeValue);
      return `<article class="sales-metric"><span>${label}</span><strong>${value}</strong><small><span class="sales-change ${change.cls}">${change.arrow} ${change.text}</span><span>${note}</span></small></article>`;
    }

    function renderSales() {
      if (!state.sales) return;
      const { meta, summary } = state.sales;
      $("#salesSkuCount").textContent = integer(summary.activeSkus);
      $("#salesStatusHeadline").textContent = meta.sampleDays >= meta.periodDays
        ? "当前周期订单数据完整"
        : `当前周期包含 ${meta.sampleDays} 个销量日`;
      $("#salesStatusDetail").textContent = `销售日期读取 ${meta.dateSource}，销售额读取 ${meta.priceSource}。`;
      $("#salesDateRange").textContent = `${meta.currentStart} 至 ${meta.currentEnd}`;
      $("#salesMetricGrid").innerHTML = [
        salesMetricCard("销售件数", `${integer(summary.units)} 件`, summary.unitsChangePct, "较前一周期"),
        salesMetricCard("订单销售额", money(summary.revenue, 2), summary.revenueChangePct, "Qty × Item Cost"),
        salesMetricCard("日均销量", `${number(summary.dailyUnits).toFixed(2)} 件`, summary.unitsChangePct, `${meta.periodDays} 个自然日`),
        salesMetricCard("成交均价", money(summary.averageUnitPrice, 2), summary.averagePriceChangePct, "按销售件数加权"),
        salesMetricCard("动销 SKU", `${integer(summary.activeSkus)} / ${integer(summary.knownSkus)}`, summary.activeSkuChangePct, `动销率 ${percent(summary.activeRate)}`),
        salesMetricCard("订单数", integer(summary.orders), summary.ordersChangePct, `${integer(summary.activeParents)} 个活跃款号`),
      ].join("");
      const trendRows = (state.sales.trendDaily && state.sales.trendDaily.length) ? state.sales.trendDaily : state.sales.daily;
      renderSalesTrend(trendRows, trendRows.length);
      renderSalesRanking();
      const dimensionSkuRows = productLibrarySkuRows();
      renderSalesDimensions(aggregateSalesDimensions(dimensionSkuRows, true), "#salesColorList");
      renderSalesDimensions(aggregateSalesDimensions(dimensionSkuRows, false), "#salesSizeList");
      renderSalesTable();
      updatePageMeta();
    }

    function renderSalesTrend(rows, sampleDays) {
      const chart = $("#salesTrendChart");
      if (!sampleDays || !rows?.length) {
        chart.innerHTML = `<div class="empty-state"><strong>当前周期没有订单销量</strong>导入包含 Order Date 的订单表后，这里会展示每日销量与订单销售额。</div>`;
        return;
      }
      const width = 900, height = 282, left = 58, right = 70, top = 22, bottom = 38;
      const plotW = width - left - right, plotH = height - top - bottom;
      const maxUnits = Math.max(...rows.map((row) => number(row.units)), 1) * 1.1;
      const maxRevenue = Math.max(...rows.map((row) => number(row.revenue)), 1) * 1.1;
      const xFor = (index) => rows.length === 1 ? left + plotW / 2 : left + index / (rows.length - 1) * plotW;
      const unitsPoints = rows.map((row, index) => ({
        x: xFor(index),
        y: top + plotH - number(row.units) / maxUnits * plotH,
        row,
      }));
      const revenuePoints = rows.map((row, index) => ({
        x: xFor(index),
        y: top + plotH - number(row.revenue) / maxRevenue * plotH,
        row,
      }));
      const path = (points) => points.map((item, index) => `${index ? "L" : "M"}${item.x.toFixed(1)},${item.y.toFixed(1)}`).join(" ");
      const area = `${path(unitsPoints)} L${unitsPoints.at(-1).x.toFixed(1)},${top + plotH} L${unitsPoints[0].x.toFixed(1)},${top + plotH} Z`;
      const grid = [0, .25, .5, .75, 1].map((step) => {
        const y = top + plotH * step;
        return `<line class="chart-grid-line" x1="${left}" x2="${left + plotW}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${integer(maxUnits * (1 - step))}</text><text class="chart-axis-label" x="${width - right + 8}" y="${y + 3}" text-anchor="start">${money(maxRevenue * (1 - step), 0)}</text>`;
      }).join("");
      const labelEvery = Math.max(1, Math.ceil(rows.length / 6));
      const labels = rows.map((row, index) => index % labelEvery === 0 || index === rows.length - 1
        ? `<text class="chart-axis-label" x="${xFor(index)}" y="${height - 10}" text-anchor="middle">${dateText(row.date)}</text>`
        : "").join("");
      chart.innerHTML = `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="订单销量与订单销售额趋势">${grid}<path class="sales-area-units" d="${area}"/><path class="sales-line-units" d="${path(unitsPoints)}"/><path class="sales-line-revenue" d="${path(revenuePoints)}"/>${labels}<line class="sales-chart-crosshair" id="salesTrendCrosshair" x1="${left}" x2="${left}" y1="${top}" y2="${top + plotH}" hidden/><g id="salesTrendMarkers"></g><rect class="sales-chart-hit-target" id="salesTrendHitTarget" x="${left}" y="${top}" width="${plotW}" height="${plotH}" fill="transparent" tabindex="0" role="slider" aria-label="销量日期，使用左右方向键查看" aria-valuemin="1" aria-valuemax="${rows.length}" aria-valuenow="${rows.length}"/></svg><div class="sales-tooltip" id="salesTrendTooltip" role="status" aria-live="polite"></div>`;

      const svg = chart.querySelector("svg");
      const hitTarget = $("#salesTrendHitTarget");
      const crosshair = $("#salesTrendCrosshair");
      const markers = $("#salesTrendMarkers");
      const tooltip = $("#salesTrendTooltip");
      let activeIndex = rows.length - 1;
      const showPoint = (index) => {
        activeIndex = Math.max(0, Math.min(rows.length - 1, index));
        const row = rows[activeIndex];
        const unitsPoint = unitsPoints[activeIndex];
        const revenuePoint = revenuePoints[activeIndex];
        crosshair.setAttribute("x1", unitsPoint.x);
        crosshair.setAttribute("x2", unitsPoint.x);
        crosshair.hidden = false;
        markers.innerHTML = `<circle class="sales-chart-marker" cx="${unitsPoint.x}" cy="${unitsPoint.y}" r="4.5" fill="var(--walmart-blue)"/><circle class="sales-chart-marker" cx="${revenuePoint.x}" cy="${revenuePoint.y}" r="4.5" fill="var(--spark-yellow)"/>`;
        tooltip.innerHTML = `<strong>${fullDateText(row.date)}</strong><div class="sales-tooltip-row"><span>销量</span><b>${integer(row.units)} 件</b></div><div class="sales-tooltip-row"><span>订单销售额</span><b>${money(row.revenue, 2)}</b></div><div class="sales-tooltip-row"><span>订单数</span><b>${integer(row.orders)}</b></div><div class="sales-tooltip-row"><span>成交均价</span><b>${money(row.averageUnitPrice, 2)}</b></div>`;
        const tooltipHalf = Math.min(108, Math.max(92, chart.clientWidth / 4));
        const tooltipX = unitsPoint.x / width * chart.clientWidth;
        tooltip.style.left = `${Math.max(tooltipHalf, Math.min(chart.clientWidth - tooltipHalf, tooltipX))}px`;
        tooltip.classList.add("show");
        hitTarget.setAttribute("aria-valuenow", String(activeIndex + 1));
        hitTarget.setAttribute("aria-valuetext", `${fullDateText(row.date)}，销量 ${integer(row.units)} 件，订单销售额 ${money(row.revenue, 2)}，订单数 ${integer(row.orders)}`);
      };
      const hidePoint = () => {
        crosshair.hidden = true;
        markers.innerHTML = "";
        tooltip.classList.remove("show");
      };
      const indexFromPointer = (event) => {
        const rect = svg.getBoundingClientRect();
        const pointerX = (event.clientX - rect.left) / rect.width * width;
        return unitsPoints.reduce((nearest, point, index) => (
          Math.abs(point.x - pointerX) < Math.abs(unitsPoints[nearest].x - pointerX) ? index : nearest
        ), 0);
      };
      hitTarget.addEventListener("pointermove", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerdown", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerleave", () => {
        if (document.activeElement !== hitTarget) hidePoint();
      });
      hitTarget.addEventListener("focus", () => showPoint(activeIndex));
      hitTarget.addEventListener("blur", hidePoint);
      hitTarget.addEventListener("keydown", (event) => {
        if (["ArrowLeft", "ArrowRight", "Home", "End", "Escape"].includes(event.key)) event.preventDefault();
        if (event.key === "ArrowLeft") showPoint(activeIndex - 1);
        if (event.key === "ArrowRight") showPoint(activeIndex + 1);
        if (event.key === "Home") showPoint(0);
        if (event.key === "End") showPoint(rows.length - 1);
        if (event.key === "Escape") hidePoint();
      });
    }

    function renderSalesRanking() {
      if (!state.sales) return;
      const metric = state.salesRankMetric;
      const productParents = new Set((state.products?.products || []).map((p) => p.parentSku).filter(Boolean));
      let rankRows = state.sales.parentRows;
      if (productParents.size > 0) rankRows = rankRows.filter((row) => productParents.has(row.parentSku));
      const rows = rankRows
        .filter((row) => number(row[metric]) > 0)
        .slice()
        .sort((a, b) => number(b[metric]) - number(a[metric]))
        .slice(0, 8);
      $$("#salesRankMetric [data-sales-rank]").forEach((button) => {
        const active = button.dataset.salesRank === metric;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
      });
      if (!rows.length) {
        $("#salesRankList").innerHTML = `<div class="empty-state"><strong>当前周期没有销量</strong>切换分析周期或导入订单数据。</div>`;
        return;
      }
      const max = Math.max(...rows.map((row) => number(row[metric])), 1);
      $("#salesRankList").innerHTML = rows.map((row) => {
        const image = row.imageUrl
          ? `<img class="sales-rank-thumb" src="${escapeHtml(row.imageUrl)}" alt="" loading="lazy">`
          : `<span class="sales-rank-thumb" aria-hidden="true"></span>`;
        const value = metric === "revenue" ? money(row.revenue, 2) : `${integer(row.units)} 件`;
        return `<div class="sales-rank-row">${image}<div class="sales-rank-copy"><strong>${escapeHtml(row.parentSku)}</strong></div><span class="sales-rank-value">${value}</span><div class="sales-rank-track"><span style="width:${number(row[metric]) / max * 100}%"></span></div></div>`;
      }).join("");
    }

    function productLibrarySkuRows() {
      if (!state.sales) return [];
      const productParents = new Set((state.products?.products || []).map((p) => p.parentSku).filter(Boolean));
      const all = state.sales.skuRows || [];
      return productParents.size > 0 ? all.filter((row) => productParents.has(row.parentSku)) : all;
    }

    function aggregateSalesDimensions(skuRows, useColor) {
      const groups = new Map();
      for (const row of skuRows) {
        const raw = useColor ? row.color : row.size;
        const text = raw == null ? "" : String(raw).trim();
        const label = text === "" ? "未填写" : text;
        const entry = groups.get(label) || { label, units: 0 };
        entry.units += number(row.units);
        groups.set(label, entry);
      }
      const rows = [...groups.values()].sort((a, b) => b.units - a.units).slice(0, 12);
      const total = rows.reduce((sum, row) => sum + row.units, 0);
      return rows.map((row) => ({ label: row.label, units: row.units, sharePct: total > 0 ? row.units / total : 0 }));
    }

    function renderSalesDimensions(rows, target) {
      const active = (rows || []).filter((row) => number(row.units) > 0).slice(0, 8);
      if (!active.length) {
        $(target).innerHTML = `<div class="empty-state"><strong>当前周期没有可归集销量</strong>产品颜色或尺码缺失时会归入“未填写”。</div>`;
        return;
      }
      const max = Math.max(...active.map((row) => number(row.units)), 1);
      $(target).innerHTML = active.map((row) => `<div class="sales-dimension-row"><strong title="${escapeHtml(row.label)}">${escapeHtml(row.label)}</strong><span>${integer(row.units)} 件 · ${percent(row.sharePct)}</span><div class="sales-dimension-track"><span style="width:${number(row.units) / max * 100}%"></span></div></div>`).join("");
    }

    function salesFilteredRows() {
      if (!state.sales) return [];
      const allRows = state.salesGroup === "parent" ? state.sales.parentRows : state.sales.skuRows;
      const productParents = new Set((state.products?.products || []).map((p) => p.parentSku).filter(Boolean));
      const source = productParents.size > 0 ? allRows.filter((row) => productParents.has(row.parentSku)) : allRows;
      const query = state.salesSearch;
      const filtered = source.filter((row) => {
        const key = state.salesGroup === "parent" ? row.parentSku : row.sku;
        return (state.salesStatus === "all" || row.status === state.salesStatus)
          && fuzzySearchMatches(query, key, row.parentSku, row.sku, row.itemName, row.itemId, row.color, row.size);
      });
      const direction = state.salesSortDirection === "asc" ? 1 : -1;
      return filtered.slice().sort((a, b) => {
        const leftKey = state.salesGroup === "parent" ? a.parentSku : a.sku;
        const rightKey = state.salesGroup === "parent" ? b.parentSku : b.sku;
        if (state.salesSort === "style") return compareStyleNo(leftKey, rightKey) * direction;
        const left = a[state.salesSort];
        const right = b[state.salesSort];
        const leftNumber = left === null || left === undefined ? Number.NEGATIVE_INFINITY : number(left);
        const rightNumber = right === null || right === undefined ? Number.NEGATIVE_INFINITY : number(right);
        if (leftNumber !== rightNumber) return (leftNumber - rightNumber) * direction;
        return compareStyleNo(leftKey, rightKey);
      });
    }

    function renderSalesTable() {
      if (!state.sales) return;
      const rows = salesFilteredRows();
      const totalPages = Math.max(1, Math.ceil(rows.length / state.salesPageSize));
      state.salesPage = Math.min(state.salesPage, totalPages - 1);
      const start = state.salesPage * state.salesPageSize;
      const pageRows = rows.slice(start, start + state.salesPageSize);
      const groupLabel = state.salesGroup === "parent" ? "款号" : "SKU";
      const inventorySnapshot = state.sales.meta.inventorySnapshotAt
        ? new Date(state.sales.meta.inventorySnapshotAt).toLocaleString("zh-CN", { hour12: false })
        : "暂无库存快照";
      const inventoryScope = state.salesGroup === "parent" ? "款号库存为所属 SKU 合计" : "库存对应当前 SKU";
      $("#salesTableSubtitle").textContent = `${analysisPeriodLabel()} 销量范围，按${groupLabel}展示；${inventoryScope}，取 ${inventorySnapshot} 最新快照，不随销量周期累计。`;
      $$("#salesGroupControls [data-sales-group]").forEach((button) => {
        const active = button.dataset.salesGroup === state.salesGroup;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
      });
      $$("#salesStatusFilters [data-sales-status]").forEach((button) => button.classList.toggle("active", button.dataset.salesStatus === state.salesStatus));
      $$(".sales-sort-button").forEach((button) => {
        const active = button.dataset.salesSort === state.salesSort;
        button.classList.toggle("active", active);
        button.classList.toggle("asc", active && state.salesSortDirection === "asc");
        const header = button.closest("th");
        if (active) header.setAttribute("aria-sort", state.salesSortDirection === "asc" ? "ascending" : "descending");
        else header.removeAttribute("aria-sort");
      });
      $("#salesTableBody").innerHTML = pageRows.map((row) => {
        const key = state.salesGroup === "parent" ? row.parentSku : row.sku;
        const image = row.imageUrl
          ? `<img class="sales-thumb" src="${escapeHtml(row.imageUrl)}" alt="" loading="lazy">`
          : `<span class="sales-thumb" aria-hidden="true"></span>`;
        const variantMeta = state.salesGroup === "parent"
          ? `${integer(row.variantCount)} SKU · ${integer(row.colorCount)} 色 · ${integer(row.sizeCount)} 码`
          : `${escapeHtml(row.color || "颜色未填")} · ${escapeHtml(row.size || "尺码未填")} · 款号 ${escapeHtml(row.parentSku || "")}`;
        const status = salesStatusCopy[row.status] || salesStatusCopy.STABLE;
        const change = salesChange(row.unitsChangePct);
        const action = state.salesGroup === "parent" ? "drill" : "product";
        return `<tr>
          <td><div class="sales-product">${image}<div><button class="sales-product-key" type="button" data-sales-row-action="${action}" data-sales-parent="${escapeHtml(row.parentSku || key)}">${escapeHtml(key)}</button><span class="sales-variant-meta">${variantMeta}</span></div></div></td>
          <td><span class="status-badge ${status.badge}">${status.label}</span></td>
          <td class="num">${integer(row.units)}</td>
          <td class="num"><div class="sales-inventory-cell" title="最新库存：${integer(row.availableUnits)} 可售，${integer(row.transitUnits)} 路途"><strong>${integer(row.availableUnits)} 可售</strong><span>${integer(row.transitUnits)} 路途</span></div></td>
          <td class="num">${daysText(row.availableDays)}</td>
          <td class="num">${number(row.dailyUnits).toFixed(2)}</td>
          <td class="num">${money(row.revenue, 2)}</td>
          <td class="num"><div class="sales-share"><div class="sales-share-track"><span style="width:${Math.min(100, number(row.sharePct) * 100)}%"></span></div><b>${percent(row.sharePct)}</b></div></td>
          <td class="num">${integer(row.previousUnits)}</td>
          <td class="num"><span class="sales-table-delta ${change.cls}">${change.arrow} ${change.text}</span></td>
          <td class="num">${money(row.averageUnitPrice, 2)}</td>
        </tr>`;
      }).join("") || emptyRow(11, "没有符合条件的销量记录", "调整搜索、状态或聚合维度后重试。");
      $("#salesTableInfo").textContent = `${rows.length} 条${groupLabel}记录，显示 ${rows.length ? start + 1 : 0}-${Math.min(start + state.salesPageSize, rows.length)}`;
      $("#salesPageInfo").textContent = `${state.salesPage + 1} / ${totalPages}`;
      $("#salesPrevPage").disabled = state.salesPage === 0;
      $("#salesNextPage").disabled = state.salesPage >= totalPages - 1;
      $$("#salesTableBody [data-sales-row-action]").forEach((button) => button.addEventListener("click", () => {
        const parentSku = button.dataset.salesParent;
        if (button.dataset.salesRowAction === "drill") {
          state.salesGroup = "sku";
          state.salesSearch = parentSku;
          state.salesPage = 0;
          $("#salesSearch").value = parentSku;
          renderSalesTable();
          showToast(`已下钻到款号 ${parentSku} 的 SKU 变体`);
        } else {
          openProductDrawer(parentSku);
        }
      }));
    }

    async function loadSales() {
      const requestId = ++state.salesRequestId;
      state.salesLoading = true;
      $("#salesStatusHeadline").textContent = "正在读取订单销量";
      $("#salesStatusDetail").textContent = "正在按 Order Date 汇总销售件数和订单成交价。";
      $("#salesMetricGrid").innerHTML = Array.from({ length: 6 }, () => `<div class="sales-metric sales-skeleton">读取中</div>`).join("");
      $("#salesTrendChart").innerHTML = `<div class="empty-state"><strong>正在生成销量趋势</strong>请稍候。</div>`;
      $("#salesTableBody").innerHTML = emptyRow(11, "正在读取销量明细", "请稍候。");
      try {
        const response = await fetch(`/api/sales/data?${analysisQuery()}`);
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        if (requestId !== state.salesRequestId) return;
        state.sales = payload.data;
        state.salesPage = 0;
        renderSales();
      } catch (error) {
        if (requestId !== state.salesRequestId) return;
        $("#salesStatusHeadline").textContent = "销量读取失败";
        $("#salesStatusDetail").textContent = error.message;
        $("#salesMetricGrid").innerHTML = "";
        $("#salesTrendChart").innerHTML = `<div class="empty-state"><strong>销量趋势读取失败</strong>${escapeHtml(error.message)}</div>`;
        $("#salesTableBody").innerHTML = emptyRow(11, "销量明细读取失败", escapeHtml(error.message));
      } finally {
        if (requestId === state.salesRequestId) state.salesLoading = false;
      }
    }

    function renderInventory() {
      const inventory = state.inventory;
      if (!inventory) return;
      const { meta, summary, riskCounts, rows } = inventory;
      $("#inventorySkuCount").textContent = integer(meta.skuCount);
      $("#inventorySnapshotAt").textContent = meta.snapshotAt ? `快照 ${new Date(meta.snapshotAt).toLocaleString("zh-CN")}` : "";
      $("#inventoryStatusHeadline").textContent = meta.reliableSample ? "预测销量样本已达到基础门槛" : "预测销量样本不足";
      $("#inventoryStatusDetail").textContent = meta.sampleDays
        ? `当前周期包含 ${meta.sampleDays} 个销量日。${meta.reliableSample ? "预测会提高近期销量权重。" : "预测会更多参考沃尔玛报表日销量，并标注低置信度。"}`
        : "当前没有订单销量样本，预测仅参考沃尔玛报表日销量。";
      $("#inventoryMetricGrid").innerHTML = [
        ["总库存", number(summary.availableUnits) + number(summary.transitUnits) + number(summary.productionUnits), "可售 + 路途 + 做货", ""],
        ["可售库存", summary.availableUnits, "沃尔玛 Available units", ""],
        ["路途库存", summary.transitUnits, "货件申收差异（负数自动归零）· 点击查看明细", "clickable transit"],
        ["做货库存", summary.productionUnits, "生产订单导入 · 点击查看明细", "clickable"],
        ["缺货与低库存", summary.outOfStockSkus + summary.lowStockSkus, `${summary.outOfStockSkus} 个缺货 SKU`, "attention"],
        ["预测建议补货", summary.forecastSuggestedUnits, `目标覆盖 ${meta.targetDays} 天 · 点击查看明细`, "forecast clickable"],
      ].map(([label, value, detail, className]) => {
        const clickable = className.includes("clickable");
        const metric = className.includes("forecast") ? "forecast" : className.includes("transit") ? "transit" : "production";
        return `<article class="inventory-metric ${className}" ${clickable ? `data-metric="${metric}" role="button" tabindex="0"` : ""}><span>${label}</span><strong>${integer(value)}</strong><small>${detail}</small></article>`;
      }).join("");
      $("#inventoryForecastCompare").innerHTML = `
        <div class="forecast-side predicted clickable" data-metric="forecast" role="button" tabindex="0">
          <strong>${units(summary.forecastSuggestedUnits)}</strong>
          <p>根据 ${meta.analysisDays} 天近期销量、报表日销量和三段库存链路计算。当前置信度：${meta.reliableSample ? "中及以上" : "低"}。<span class="fc-detail-hint">点击查看补货款式明细 ›</span></p>
        </div>
        <button type="button" class="workbench-entry" data-workbench-link>打开需求预测工作台维护因子 ›</button>`;
      renderInventoryRisk(riskCounts, meta.skuCount);
      renderTransitInventory(inventory.transit);
      renderInventoryTrend(inventory.trend);
      renderInventoryByStyle(rows);
      updatePageMeta();
    }

    function transitSource(source) {
      const labels = {
        MANUAL: ["人工维护", "manual"],
        NEEDS_MANUAL: ["待维护", "needs-manual"],
        UNMATCHED: ["未匹配物流", "unmatched"],
      };
      const [label, className] = labels[source] || ["待维护", "needs-manual"];
      return `<span class="transit-source ${className}">${label}</span>`;
    }

    function transitDateText(value) {
      return value ? new Date(`${value}T00:00:00`).toLocaleDateString("zh-CN") : "—";
    }

    function transitDateTimeText(value) {
      return value ? new Date(value).toLocaleString("zh-CN") : "—";
    }

    function transitDaysUntil(value) {
      if (!value) return null;
      const today = new Date(); today.setHours(0, 0, 0, 0);
      const target = new Date(`${value}T00:00:00`); target.setHours(0, 0, 0, 0);
      return Math.round((target - today) / 86400000);
    }

    function transitCountdownText(value) {
      const days = transitDaysUntil(value);
      if (days == null) return "—";
      if (days < 0) return `已超 ${Math.abs(days)} 天`;
      if (days === 0) return "今天到";
      return `还有 ${days} 天`;
    }

    function transitCountdownClass(value) {
      const days = transitDaysUntil(value);
      if (days == null) return "";
      if (days < 0) return "cd-overdue";
      if (days <= 7) return "cd-urgent";
      if (days <= 30) return "cd-soon";
      return "cd-far";
    }

    // 货件「还有几天到」单元格：入库中的货件已到仓，不再显示倒计时（避免显示误导性的 0 / 已超）
    function transitCountdownCell(shipment) {
      if ((shipment.localStatus || "").trim() === "入库中") {
        return `<span class="transit-countdown cd-arrived">已入库</span>`;
      }
      const cls = transitCountdownClass(shipment.estimatedArrival);
      const text = transitCountdownText(shipment.estimatedArrival);
      return `<span class="transit-countdown ${cls}">${text}</span>`;
    }

    function renderTransitInventory(transit) {
      const channelBox = $("#transitChannelLeadTime");
      const shipmentBox = $("#transitShipmentList");
      const channels = transit?.channels || [];
      const shipments = transit?.shipments || [];
      const parentQuery = state.transitShipmentParentQuery.trim().toLowerCase();
      const parentFiltered = parentQuery
        ? shipments.filter((shipment) => (shipment.parentSkus || []).some((parentSku) => String(parentSku || "").toLowerCase().includes(parentQuery)))
        : shipments;
      channelBox.innerHTML = channels.length ? `<table class="transit-channel-table"><thead><tr>
          <th>渠道</th><th class="num">到达时效</th><th>维护状态</th><th>更新时间</th><th>维护</th>
        </tr></thead><tbody>${channels.map((channel, index) => `<tr>
          <td title="${escapeHtml(channel.channelName || "")}"><span class="transit-channel-name">${escapeHtml(channel.channelName || "—")}</span></td>
          <td class="num"><input class="transit-channel-input" data-transit-lead-input="${index}" type="number" min="1" max="180" step="0.5" value="${channel.leadDays == null ? "" : escapeHtml(channel.leadDays)}" aria-label="${escapeHtml(channel.channelName || "渠道")} 到达时效（天）" placeholder="天"></td>
          <td>${transitSource(channel.source)}</td>
          <td class="mono">${transitDateTimeText(channel.updatedAt)}</td>
          <td><button type="button" class="table-link" data-save-transit-channel="${index}">保存时效</button></td>
        </tr>`).join("")}</tbody></table>` : `<div class="empty-state"><strong>暂无可维护渠道</strong>扫描导入物流信息登记表后，系统会按渠道生成时效规则。</div>`;
      const BUCKETS = [
        { key: "b7", label: "7 天内", lo: 0, hi: 7 },
        { key: "b14", label: "8–14 天", lo: 8, hi: 14 },
        { key: "b30", label: "15–30 天", lo: 15, hi: 30 },
        { key: "b60", label: "31–60 天", lo: 31, hi: 60 },
      ];
      const sortByArrival = (list) => [...list].sort((a, b) => {
        const av = a.estimatedArrival || "9999-12-31";
        const bv = b.estimatedArrival || "9999-12-31";
        return av < bv ? -1 : av > bv ? 1 : 0;
      });
      const activeBucket = BUCKETS.find((bk) => bk.key === state.transitBucketFilter) || null;
      const tableShipments = activeBucket
        ? parentFiltered.filter((s) => {
            const d = transitDaysUntil(s.estimatedArrival);
            return d != null && d >= activeBucket.lo && d <= activeBucket.hi;
          })
        : parentFiltered;
      const sortedShipments = sortByArrival(tableShipments);
      const bucketHtml = parentFiltered.length ? `<div class="transit-buckets" role="list">${BUCKETS.map((bk) => {
        const items = sortByArrival(parentFiltered).filter((s) => {
          const d = transitDaysUntil(s.estimatedArrival);
          return d != null && d >= bk.lo && d <= bk.hi;
        });
        const isActive = activeBucket && activeBucket.key === bk.key;
        return `<div class="transit-bucket ${bk.key} clickable${items.length ? "" : " empty"}${isActive ? " active" : ""}" role="button" tabindex="0" data-bucket="${bk.key}" aria-pressed="${isActive}" title="点击只看 ${bk.label} 到达的货件">
          <span class="bk-label">${bk.label}</span>
          <strong class="bk-units">${integer(sum(items, "units"))}</strong>
          <span class="bk-count">${items.length} 批</span>
        </div>`;
      }).join("")}</div>${activeBucket ? `<button class="transit-bucket-clear" type="button" data-clear-bucket="1">× 仅看：${activeBucket.label}（清除）</button>` : ""}` : "";
      shipmentBox.innerHTML = sortedShipments.length
        ? bucketHtml + `<table class="transit-shipment-table"><thead><tr>
          <th>货件编号</th><th>状态</th><th>货代 / 渠道</th><th>款号</th><th class="num">路途数量</th><th class="num">SKU</th><th>预计开船</th><th class="num">到达时效</th><th>预计到达</th><th class="num">还有几天到</th>
        </tr></thead><tbody>${sortedShipments.map((shipment) => `<tr>
          <td class="mono">${escapeHtml(shipment.shipmentNo || "—")}</td>
          <td>${escapeHtml(shipment.localStatus || "—")}</td>
          <td title="${escapeHtml(shipment.channel || "")}"><strong>${escapeHtml(shipment.forwarder || "未匹配货代")}</strong><br><span class="muted">${escapeHtml(shipment.channel || "未匹配渠道")}</span></td>
          <td title="${escapeHtml((shipment.parentSkus || []).join("、"))}"><span class="transit-parent-list">${escapeHtml((shipment.parentSkus || []).join("、") || "—")}</span></td>
          <td class="num">${integer(shipment.units)}</td>
          <td class="num">${integer(shipment.skuCount)}</td>
          <td>${transitDateText(shipment.estimatedDeparture)}</td>
          <td class="num">${shipment.leadDays == null ? "—" : `${number(shipment.leadDays).toFixed(1)} 天`}</td>
          <td>${transitDateText(shipment.estimatedArrival)}</td>
          <td class="num">${transitCountdownCell(shipment)}</td>
        </tr>`).join("")}</tbody></table>`
        : `<div class="empty-state"><strong>${shipments.length ? "未找到匹配款号的货件" : "暂无路途货件"}</strong>${shipments.length ? "请调整款号筛选条件后重试。" : "导入货件列表后，这里只显示入库中、已发货和已申报的货件。"}</div>`;
    }

    function openTransitInventoryDetails(parentSku) {
      const drawer = $("#transitDrawer");
      const input = $("#transitShipmentParentFilter");
      if (parentSku) {
        state.transitShipmentParentQuery = parentSku;
        if (input) input.value = parentSku;
      } else {
        state.transitShipmentParentQuery = "";
        if (input) input.value = "";
      }
      state.transitBucketFilter = "";
      renderTransitInventory(state.inventory?.transit);
      const channels = state.inventory?.transit?.channels || [];
      const hasUnfilledChannel = channels.some((c) => c.leadDays == null);
      setTransitChannelLeadTimeCollapsed(!hasUnfilledChannel);
      if (drawer && !drawer.open) drawer.showModal();
    }

    function setTransitChannelLeadTimeCollapsed(collapsed) {
      const content = $("#transitChannelLeadTimeContent");
      const button = $("#toggleTransitChannelLeadTime");
      if (!content || !button) return;
      content.hidden = collapsed;
      button.textContent = collapsed ? "展开" : "收起";
      button.setAttribute("aria-expanded", String(!collapsed));
    }

    async function saveTransitChannelLeadTime(index, button) {
      const transit = state.inventory?.transit;
      const channel = transit?.channels?.[index];
      const input = document.querySelector(`[data-transit-lead-input="${index}"]`);
      const leadDays = Number(input?.value);
      if (!channel || !Number.isFinite(leadDays) || leadDays <= 0 || leadDays > 180) {
        showToast("请填写 1 到 180 天之间的渠道时效");
        input?.focus();
        return;
      }
      button.disabled = true;
      try {
        const response = await fetch(`/api/transit/channels/${encodeURIComponent(channel.channelName)}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ leadDays }),
        });
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        await fetchInventoryData();
        renderInventory();
        showToast(`已保存「${channel.channelName}」的人工到达时效`);
      } catch (error) {
        showToast(`渠道时效保存失败：${error.message}`);
      } finally {
        button.disabled = false;
      }
    }

    function renderInventoryRisk(counts, total) {
      const ordered = ["OUT_OF_STOCK", "URGENT", "LOW", "WATCH", "REPLENISH", "OVERSTOCK", "NO_DEMAND", "HEALTHY"];
      const max = Math.max(...ordered.map((key) => number(counts[key])), 1);
      $("#inventoryRiskList").innerHTML = ordered.map((key) => {
        const copy = inventoryRiskCopy[key];
        const count = number(counts[key]);
        return `<div class="risk-row ${copy.group}"><span>${copy.label}</span><div class="risk-track" title="${count} / ${total}"><span style="width:${count / max * 100}%"></span></div><strong>${integer(count)}</strong></div>`;
      }).join("");
    }

    function renderInventoryTrend(trend) {
      if (!trend?.length) {
        $("#inventoryTrend").innerHTML = `<div class="empty-state"><strong>还没有库存快照</strong>扫描导入库存文件后，这里会展示历史变化。</div>`;
        return;
      }
      const allRows = trend.slice().sort((a, b) => new Date(a.snapshotAt) - new Date(b.snapshotAt));
      const latestTime = Math.max(...allRows.map((row) => new Date(row.snapshotAt).getTime()));
      const rangeDays = state.inventoryTrendRange === "all" ? null : number(state.inventoryTrendRange);
      const cutoff = rangeDays ? latestTime - rangeDays * 86400000 : Number.NEGATIVE_INFINITY;
      const rows = allRows.filter((row) => new Date(row.snapshotAt).getTime() >= cutoff);
      const series = [
        { key: "total", label: "总库存", className: "inventory-line-total", color: "var(--frost-ink)", value: (row) => number(row.availableUnits) + number(row.transitUnits) + number(row.productionUnits) },
        { key: "available", label: "可售库存", className: "inventory-line-available", color: "var(--walmart-blue)", value: (row) => number(row.availableUnits) },
        { key: "transit", label: "路途库存", className: "inventory-line-transit", color: "var(--spark-yellow)", value: (row) => number(row.transitUnits) },
        { key: "production", label: "做货库存", className: "inventory-line-production", color: "var(--production-color)", value: (row) => number(row.productionUnits) },
      ];
      const activeSeries = series.filter((item) => state.inventoryTrendSeries.has(item.key));
      $$("#inventoryTrendRanges [data-inventory-range]").forEach((button) => {
        const active = button.dataset.inventoryRange === state.inventoryTrendRange;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
      });
      $$("#inventoryTrendLegend [data-inventory-series]").forEach((button) => {
        const active = state.inventoryTrendSeries.has(button.dataset.inventorySeries);
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
      });

      const chartBox = $("#inventoryTrend");
      const chartBoxCs = getComputedStyle(chartBox);
      const paddingLeft = Number.parseFloat(chartBoxCs.paddingLeft) || 0;
      const paddingRight = Number.parseFloat(chartBoxCs.paddingRight) || 0;
      const chartInnerWidth = Math.max(0, chartBox.clientWidth - paddingLeft - paddingRight);
      const width = Math.max(360, Math.round(chartInnerWidth)), height = 280, left = 62, right = 22, top = 22, bottom = 38;
      const plotW = width - left - right, plotH = height - top - bottom;
      const timestamps = rows.map((row) => new Date(row.snapshotAt).getTime());
      const minTime = Math.min(...timestamps), maxTime = Math.max(...timestamps);
      const rawMax = Math.max(...rows.flatMap((row) => activeSeries.map((item) => item.value(row))), 1);
      const mag = Math.pow(10, Math.floor(Math.log10(rawMax)));
      const n = rawMax / mag;
      const nice = n <= 1 ? 1 : n <= 1.2 ? 1.2 : n <= 1.5 ? 1.5 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 3 ? 3 : n <= 4 ? 4 : n <= 5 ? 5 : n <= 6 ? 6 : n <= 8 ? 8 : 10;
      const maxValue = Math.ceil(nice * mag);
      const xFor = (row, index) => maxTime === minTime ? left + plotW / 2 : left + (timestamps[index] - minTime) / (maxTime - minTime) * plotW;
      const point = (row, index, item) => ({
        x: xFor(row, index),
        y: top + plotH - item.value(row) / maxValue * plotH,
        value: item.value(row),
        row,
      });
      const pointsBySeries = new Map(activeSeries.map((item) => [item.key, rows.map((row, index) => point(row, index, item))]));
      const path = (points) => points.map((item, index) => `${index ? "L" : "M"}${item.x.toFixed(1)},${item.y.toFixed(1)}`).join(" ");
      const grid = [0, .25, .5, .75, 1].map((step) => {
        const y = top + plotH * step;
        return `<line class="chart-grid-line" x1="${left}" x2="${left + plotW}" y1="${y}" y2="${y}"/><text class="chart-axis-label" x="${left - 8}" y="${y + 3}" text-anchor="end">${integer(maxValue * (1 - step))}</text>`;
      }).join("");
      const labelEvery = Math.max(1, Math.ceil(rows.length / 6));
      const labels = rows.map((row, index) => index % labelEvery === 0 || index === rows.length - 1
        ? `<text class="chart-axis-label" x="${xFor(row, index)}" y="${height - 10}" text-anchor="middle">${new Date(row.snapshotAt).toLocaleDateString("zh-CN", { year: "2-digit", month: "numeric", day: "numeric" })}</text>` : "").join("");
      const paths = activeSeries.map((item) => `<path class="${item.className}" d="${path(pointsBySeries.get(item.key))}"/>`).join("");
      const singleDots = rows.length === 1 ? activeSeries.map((item) => {
        const itemPoint = pointsBySeries.get(item.key)[0];
        return `<circle class="inventory-trend-marker" cx="${itemPoint.x}" cy="${itemPoint.y}" r="4" fill="${item.color}"/>`;
      }).join("") : "";
      const chart = $("#inventoryTrend");
      chart.innerHTML = `<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="总库存、可售库存和路途库存变化曲线">${grid}${paths}${singleDots}${labels}<line class="inventory-crosshair" id="inventoryCrosshair" x1="${left}" x2="${left}" y1="${top}" y2="${top + plotH}" hidden/><g id="inventoryTrendMarkers"></g><rect class="inventory-trend-hit-target" id="inventoryTrendHitTarget" x="${left}" y="${top}" width="${plotW}" height="${plotH}" fill="transparent" tabindex="0" role="slider" aria-label="库存快照，使用左右方向键查看" aria-valuemin="1" aria-valuemax="${rows.length}" aria-valuenow="${rows.length}"/></svg><div class="inventory-trend-tooltip" id="inventoryTrendTooltip" role="status" aria-live="polite"></div>`;

      const svg = chart.querySelector("svg");
      const hitTarget = $("#inventoryTrendHitTarget");
      const crosshair = $("#inventoryCrosshair");
      const markers = $("#inventoryTrendMarkers");
      const tooltip = $("#inventoryTrendTooltip");
      let activeIndex = rows.length - 1;
      const showPoint = (index) => {
        activeIndex = Math.max(0, Math.min(rows.length - 1, index));
        const row = rows[activeIndex];
        const x = xFor(row, activeIndex);
        crosshair.setAttribute("x1", x);
        crosshair.setAttribute("x2", x);
        crosshair.hidden = false;
        markers.innerHTML = activeSeries.map((item) => {
          const itemPoint = pointsBySeries.get(item.key)[activeIndex];
          return `<circle class="inventory-trend-marker" cx="${itemPoint.x}" cy="${itemPoint.y}" r="4.5" fill="${item.color}"/>`;
        }).join("");
        const date = new Date(row.snapshotAt).toLocaleDateString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit" });
        const values = activeSeries.map((item) => ({ ...item, displayValue: item.value(row) }));
        tooltip.innerHTML = `<strong>${date}</strong>${values.map((item) => `<div class="inventory-tooltip-row"><span><i class="inventory-tooltip-dot ${item.key}"></i>${item.label}</span><b>${integer(item.displayValue)}</b></div>`).join("")}`;
        const svgRect = svg.getBoundingClientRect();
        const chartRect = chart.getBoundingClientRect();
        const tooltipHalf = Math.min(112, Math.max(94, svgRect.width / 4));
        const tooltipX = (svgRect.left - chartRect.left) + (x / width) * svgRect.width;
        tooltip.style.left = `${Math.max(tooltipHalf, Math.min(chart.clientWidth - tooltipHalf, tooltipX))}px`;
        tooltip.classList.add("show");
        hitTarget.setAttribute("aria-valuenow", String(activeIndex + 1));
        hitTarget.setAttribute("aria-valuetext", `${date}，${values.map((item) => `${item.label} ${integer(item.displayValue)}`).join("，")}`);
      };
      const hidePoint = () => {
        crosshair.hidden = true;
        markers.innerHTML = "";
        tooltip.classList.remove("show");
      };
      const indexFromPointer = (event) => {
        const rect = svg.getBoundingClientRect();
        const pointerX = (event.clientX - rect.left) / rect.width * width;
        let nearest = 0;
        let distance = Number.POSITIVE_INFINITY;
        rows.forEach((row, index) => {
          const currentDistance = Math.abs(xFor(row, index) - pointerX);
          if (currentDistance < distance) {
            distance = currentDistance;
            nearest = index;
          }
        });
        return nearest;
      };
      hitTarget.addEventListener("pointermove", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerdown", (event) => showPoint(indexFromPointer(event)));
      hitTarget.addEventListener("pointerleave", () => {
        if (document.activeElement !== hitTarget) hidePoint();
      });
      hitTarget.addEventListener("focus", () => showPoint(activeIndex));
      hitTarget.addEventListener("blur", hidePoint);
      hitTarget.addEventListener("keydown", (event) => {
        if (["ArrowLeft", "ArrowRight", "Home", "End", "Escape"].includes(event.key)) event.preventDefault();
        if (event.key === "ArrowLeft") showPoint(activeIndex - 1);
        if (event.key === "ArrowRight") showPoint(activeIndex + 1);
        if (event.key === "Home") showPoint(0);
        if (event.key === "End") showPoint(rows.length - 1);
        if (event.key === "Escape") hidePoint();
      });
    }

    function inventoryFilteredRows(rows) {
      const query = state.inventorySearch;
      const risks = state.inventoryRisk === "all" ? null : state.inventoryRisk.split(",");
      return rows.filter((row) => {
        const matchesRisk = !risks || risks.includes(row.risk);
        return matchesRisk && fuzzySearchMatches(
          query,
          row.sku,
          row.parentSku,
          row.itemId,
          row.itemName,
          row.status,
          row.publishingStatus,
          row.itemLifecycle,
          row.productionNote
        );
      });
    }

    // 有搜索词时返回匹配到的款号(parentSku)集合；无搜索词返回 null（表示不过滤）。
    // 字段与 inventoryFilteredRows 保持一致，确保"清单级过滤"与"明细级过滤"口径统一。
    function inventoryMatchedParentSkus() {
      const query = (state.inventorySearch || "").trim();
      if (!query) return null;
      const productParents = new Set((state.products?.products || []).map((p) => p.parentSku).filter(Boolean));
      const sourceRows = productParents.size === 0
        ? state.inventory.rows
        : state.inventory.rows.filter((r) => productParents.has(r.parentSku));
      const matched = new Set();
      for (const row of sourceRows) {
        if (fuzzySearchMatches(
          query, row.sku, row.parentSku, row.itemId, row.itemName,
          row.status, row.publishingStatus, row.itemLifecycle, row.productionNote
        )) matched.add(row.parentSku || "—");
      }
      return matched;
    }

    // 更新搜索计数标签：有搜索词显示"找到 N 个款号"，无词清空
    function updateInventorySearchCount(visibleCount) {
      const el = $("#inventorySearchCount");
      if (!el) return;
      const query = (state.inventorySearch || "").trim();
      el.textContent = query ? `找到 ${visibleCount} 个款号` : "";
    }

    function inventoryStyleSystemRisk(totalAvailable, totalSupply, totalForecastDaily, forecastSuggested, targetDays = state.inventoryTargetDays) {
      if (totalAvailable <= 0) return "OUT_OF_STOCK";
      if (totalForecastDaily <= 0) return "NO_DEMAND";
      const projectedDays = totalSupply / totalForecastDaily;
      if (projectedDays <= 7) return "URGENT";
      if (projectedDays <= 14) return "LOW";
      if (projectedDays <= 30) return "WATCH";
      if (projectedDays > targetDays * 1.5) return "OVERSTOCK";
      if (forecastSuggested > 0) return "REPLENISH";
      return "HEALTHY";
    }

    function renderInventoryByStyle(rows) {
      const riskOrder = ["OUT_OF_STOCK", "URGENT", "LOW", "WATCH", "REPLENISH", "OVERSTOCK", "NO_DEMAND", "HEALTHY"];
      const productParents = new Set((state.products?.products || []).map((p) => p.parentSku).filter(Boolean));
      const sourceRows = productParents.size === 0 ? rows : rows.filter((r) => productParents.has(r.parentSku));
      const sum = (skus, field) => skus.reduce((acc, r) => acc + number(r[field]), 0);
      const groups = new Map();
      for (const row of sourceRows) {
        const key = row.parentSku || "—";
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(row);
      }
      // 搜索词命中时，仅在清单级保留匹配的款号（之前搜索只在展开后的明细里生效，顶层款号纹丝不动）
      const matchedParents = inventoryMatchedParentSkus();
      if (matchedParents) {
        for (const key of [...groups.keys()]) {
          if (!matchedParents.has(key)) groups.delete(key);
        }
      }
      const body = $("#inventoryByStyleBody");
      if (!groups.size) {
        let emptyTitle = "没有库存数据", emptyHint;
        if (matchedParents) {
          emptyTitle = "没有匹配的款号";
          emptyHint = "试试更短的关键词，如 6066、PNK，或商品名片段。";
        } else if (productParents.size === 0) {
          emptyHint = "扫描导入库存文件后，这里会展示款号汇总。";
        } else {
          emptyHint = "当前仅展示产品库中已经匹配的款号，其余款号不展示。";
        }
        body.innerHTML = emptyRow(11, emptyTitle, emptyHint);
        updateByStyleSortIndicators();
        updateByStyleDetailSortIndicators();
        updateInventorySearchCount(0);
        return;
      }
      const aggregates = [...groups.entries()].map(([parentSku, skus]) => {
        const totalAvailable = sum(skus, "availableUnits");
        const totalTransit = sum(skus, "transitUnits");
        const production = sum(skus, "productionUnits");
        const totalStock = totalAvailable + totalTransit + production;
        const totalRecent = sum(skus, "recentUnits");
        const totalForecastDaily = sum(skus, "forecastDailyUnits");
        const fcSuggest = sum(skus, "forecastSuggestedUnits");
        const productionSuggest = sum(skus, "productionSuggestedUnits");
        const domesticShipment = sum(skus, "domesticShipmentUnits");
        const systemRisk = inventoryStyleSystemRisk(totalAvailable, totalStock, totalForecastDaily, fcSuggest);
        const outOfStockSkuCount = skus.filter((row) => number(row.availableUnits) <= 0).length;
        const projected = totalForecastDaily > 0 ? totalStock / totalForecastDaily : null;
        return { parentSku, skus, totalAvailable, totalTransit, totalStock, totalRecent, totalForecastDaily, fcSuggest, productionSuggest, domesticShipment, production, systemRisk, outOfStockSkuCount, projected };
      });
      const { key, dir } = state.inventoryByStyleSort;
      const riskIndex = (k) => riskOrder.indexOf(k);
      aggregates.sort((a, b) => {
        if (key === "parentSku") {
          const compared = compareStyleNo(a.parentSku, b.parentSku);
          return dir === "asc" ? compared : -compared;
        }
        let av, bv;
        if (key === "risk") { av = riskIndex(a.systemRisk); bv = riskIndex(b.systemRisk); }
        else { av = number(a[key]); bv = number(b[key]); }
        return dir === "asc" ? av - bv : bv - av;
      });
      const html = aggregates.map((g) => {
        const { parentSku, skus, totalAvailable, totalTransit, totalStock, totalRecent, projected, fcSuggest, productionSuggest, domesticShipment, production, systemRisk, outOfStockSkuCount } = g;
        const risk = inventoryRiskCopy[systemRisk] || inventoryRiskCopy.HEALTHY;
        const rep = skus[0];
        const image = rep.imageUrl
          ? `<img class="inventory-thumb" src="${escapeHtml(rep.imageUrl)}" alt="" loading="lazy">`
          : `<span class="inventory-thumb" aria-hidden="true"></span>`;
        const expanded = state.inventoryByStyleExpanded.has(parentSku);
        // 子表应用搜索/风险筛选；总维度聚合始终用全量 SKU
        const detailSkus = inventoryFilteredRows(skus);
        const dsk = state.inventoryByStyleDetailSort.key, ddir = state.inventoryByStyleDetailSort.dir;
        detailSkus.sort((a, b) => {
          if (dsk === "risk") return ddir === "asc" ? riskOrder.indexOf(a.risk) - riskOrder.indexOf(b.risk) : riskOrder.indexOf(b.risk) - riskOrder.indexOf(a.risk);
          if (dsk === "sku") { const av = String(a.sku || ""), bv = String(b.sku || ""); return ddir === "asc" ? av.localeCompare(bv) : bv.localeCompare(av); }
          const av = number(a[dsk]), bv = number(b[dsk]);
          return ddir === "asc" ? av - bv : bv - av;
        });
        const detailRows = detailSkus.map((r) => {
          const rRisk = inventoryRiskCopy[r.risk] || inventoryRiskCopy.HEALTHY;
          const rImage = r.imageUrl
            ? `<img class="inventory-thumb" src="${escapeHtml(r.imageUrl)}" alt="" loading="lazy">`
            : `<span class="inventory-thumb" aria-hidden="true"></span>`;
          return `<tr>
            <td><div class="inventory-product">${rImage}<div><span class="inventory-sku" title="${escapeHtml(r.sku)}">${escapeHtml(r.sku)}</span><span class="inventory-item-name" title="${escapeHtml(r.itemName || "")}">${escapeHtml(r.itemName || r.itemId || "")}</span></div></div></td>
            <td><span class="status-badge ${rRisk.badge}" title="预测置信度：${confidenceCopy[r.confidence] || r.confidence}">${rRisk.label}</span></td>
            <td class="num">${integer(r.recentUnits)}</td>
            <td class="num" title="近期日均 ${r.recentDailyUnits}，报表日销量 ${r.reportDailyUnits}">${number(r.forecastDailyUnits).toFixed(2)}</td>
            <td class="num">${integer(r.availableUnits)}</td>
            <td class="num">${integer(r.productionUnits)}</td>
            <td class="num">${integer(r.transitUnits)}</td>
            <td class="num" title="${escapeHtml([r.formula, r.productionNote].filter(Boolean).join("\n"))}">${integer(r.forecastSuggestedUnits)}</td>
            <td class="num" title="同一布色按完整裁床数向上取整；SKU 最低做货5件，之后按整数分配">${integer(r.productionSuggestedUnits)}</td>
            <td class="num">${daysText(r.projectedDaysSupply)}</td>
          </tr>`;
        }).join("") || emptyRow(10, "该款没有匹配的 SKU", "清除搜索或切换风险筛选。");
        return `<tr class="bystyle-row" data-parent="${escapeHtml(parentSku)}">
          <td><div class="inventory-product bystyle-toggle" data-parent="${escapeHtml(parentSku)}"><span class="bystyle-caret ${expanded ? "open" : ""}">▸</span>${image}<div><span class="inventory-sku" title="${escapeHtml(parentSku)}">${escapeHtml(parentSku)}</span><span class="inventory-item-name" title="${escapeHtml(rep.itemName || "")}">${escapeHtml(rep.itemName || rep.itemId || "")} · 国内仓发货 ${integer(domesticShipment)}</span></div></div></td>
          <td><span class="status-badge ${risk.badge}" title="系统按款号总库存与预测日均计算">${risk.label}</span>${outOfStockSkuCount ? `<span class="sub-id">${integer(outOfStockSkuCount)}/${integer(skus.length)} SKU 无可售</span>` : ""}</td>
          <td class="num">${integer(totalAvailable)}</td>
          <td class="num transit-cell-clickable" title="点击查看 ${escapeHtml(parentSku)} 的路途货件明细与到货倒计时" data-transit-parent="${escapeHtml(parentSku)}">${integer(totalTransit)}</td>
          <td class="num">${integer(totalStock)}</td>
          <td class="num">${integer(totalRecent)}</td>
          <td class="num">${projected != null ? projected.toFixed(1) : "—"}</td>
          <td class="num fc-suggest-jump" title="点击跳转到需求分析" data-jump-workbench="${escapeHtml(parentSku)}">${integer(fcSuggest)}</td>
          <td class="num">${integer(productionSuggest)}</td>
          <td class="num">${integer(production)}</td>
          <td><button class="table-link bystyle-toggle-btn" type="button" data-parent="${escapeHtml(parentSku)}">${expanded ? "收起" : "展开"}</button></td>
        </tr>
        <tr class="bystyle-detail-row ${expanded ? "" : "collapsed"}" data-detail-parent="${escapeHtml(parentSku)}">
          <td colspan="11"><div class="bystyle-detail-inner">
            <table class="inventory-table bystyle-detail-table">
              <thead><tr>
                <th class="bystyle-detail-sortable" data-sort="sku">SKU<span class="bystyle-detail-sort-ind"></span></th>
                <th class="bystyle-detail-sortable" data-sort="risk">风险<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="recentUnits">近期销量<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="forecastDailyUnits">预测日均<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="availableUnits">可售<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="productionUnits" title="数值由「需求分析 60 天计划」回写覆盖（裁床取整后做货量）；未覆盖 SKU 回落本地快捷算法">做货<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="transitUnits">路途<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="forecastSuggestedUnits" title="数值由「需求分析 60 天计划」回写覆盖；未覆盖 SKU 回落本地快捷算法">预测建议补货<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="productionSuggestedUnits" title="数值由「需求分析 60 天计划」回写覆盖（裁床取整后）；未覆盖 SKU 回落本地快捷算法">裁床建议做货<span class="bystyle-detail-sort-ind"></span></th>
                <th class="num bystyle-detail-sortable" data-sort="projectedDaysSupply">预测可售天数<span class="bystyle-detail-sort-ind"></span></th>
              </tr></thead>
              <tbody>${detailRows}</tbody>
            </table>
          </div></td>
        </tr>`;
      }).join("");
      body.innerHTML = html;
      updateInventorySearchCount(groups.size);
      updateByStyleSortIndicators();
      updateByStyleDetailSortIndicators();
      body.querySelectorAll(".bystyle-detail-sortable").forEach((th) => {
        th.addEventListener("click", () => {
          const key = th.dataset.sort;
          const cur = state.inventoryByStyleDetailSort;
          if (cur.key === key) cur.dir = cur.dir === "asc" ? "desc" : "asc";
          else { cur.key = key; cur.dir = (key === "risk" || key === "sku") ? "asc" : "desc"; }
          if (state.inventory) renderInventoryByStyle(state.inventory.rows);
        });
      });
      body.querySelectorAll(".bystyle-toggle, .bystyle-toggle-btn").forEach((el) => {
        el.addEventListener("click", () => {
          const parent = el.dataset.parent || el.closest("[data-parent]").dataset.parent;
          const isOpen = state.inventoryByStyleExpanded.has(parent);
          if (isOpen) state.inventoryByStyleExpanded.delete(parent);
          else state.inventoryByStyleExpanded.add(parent);
          const detailRow = body.querySelector(`[data-detail-parent="${parent}"]`);
          const row = body.querySelector(`[data-parent="${parent}"]`);
          const caret = row ? row.querySelector(".bystyle-caret") : null;
          const btn = row ? row.querySelector(".bystyle-toggle-btn") : null;
          if (detailRow) detailRow.classList.toggle("collapsed");
          if (caret) caret.classList.toggle("open");
          if (btn) btn.textContent = isOpen ? "展开" : "收起";
        });
      });
      body.querySelectorAll("[data-jump-workbench]").forEach((el) => {
        el.addEventListener("click", (e) => {
          e.stopPropagation();
          const parent = el.dataset.jumpWorkbench;
          if (!parent) return;
          state.forecastWorkbenchFilter = "all";
          state.forecastWorkbenchExpanded.add(parent);
          switchView("demand-forecast");
          renderDemandForecast();
          requestAnimationFrame(() => {
            const row = [...document.querySelectorAll("#forecastWorkbenchBody .forecast-style-row")]
              .find((r) => r.dataset.style === parent);
            if (row) {
              row.scrollIntoView({ behavior: "smooth", block: "center" });
              row.classList.add("flash-highlight");
              setTimeout(() => row.classList.remove("flash-highlight"), 1600);
            }
          });
        });
      });
    }

    function updateByStyleSortIndicators() {
      const { key, dir } = state.inventoryByStyleSort;
      $$("#inventoryByStyleHead th.bystyle-sortable").forEach((th) => {
        const active = th.dataset.sort === key;
        th.classList.toggle("active", active);
        const ind = th.querySelector(".bystyle-sort-ind");
        if (ind) ind.textContent = active ? (dir === "asc" ? "▲" : "▼") : "";
      });
    }

    function updateByStyleDetailSortIndicators() {
      const { key, dir } = state.inventoryByStyleDetailSort;
      $$(".bystyle-detail-sortable").forEach((th) => {
        const active = th.dataset.sort === key;
        th.classList.toggle("active", active);
        const ind = th.querySelector(".bystyle-detail-sort-ind");
        if (ind) ind.textContent = active ? (dir === "asc" ? "▲" : "▼") : "";
      });
    }

    function renderProductMetrics(meta) {
      const products = state.products?.products || [];
      const metrics = [
        ["产品组", integer(meta.parentProductCount), "以 Parent SKU 作为主体"],
        ["SKU 变体", integer(meta.variantCount), "保留每个颜色和尺码"],
        ["清货款", integer(products.filter((product) => product.clearance).length), "已停止国内仓发货与工厂做货"],
        ["春夏 / 秋冬", `${integer(products.filter((product) => productSeasonType(product) === "SPRING_SUMMER").length)} / ${integer(products.filter((product) => productSeasonType(product) === "AUTUMN_WINTER").length)}`, "已明确换季属性的父款"],
        ["四季款", integer(products.filter((product) => productSeasonType(product) === "ALL_SEASON").length), "全年常售经营标签"],
      ];
      $("#productMetricGrid").innerHTML = metrics.map(([label, value, foot]) => `<article class="product-metric"><span>${label}</span><strong>${value}</strong><small>${foot}</small></article>`).join("");
    }

    function productSeasonType(product) {
      return product?.seasonType || (product?.allSeason ? "ALL_SEASON" : "SEASONAL");
    }

    function productLifecycleTags(product) {
      const tags = [];
      if (product.clearance) tags.push(`<span class="product-lifecycle-tag clearance">清货款 · 停补</span>`);
      const season = productSeasonType(product);
      const seasonTag = {
        SPRING_SUMMER: ["spring-summer", "春夏款"],
        AUTUMN_WINTER: ["autumn-winter", "秋冬款"],
        ALL_SEASON: ["all-season", "四季款"],
        SEASONAL: ["seasonal", "季节待标"]
      }[season] || ["seasonal", "季节待标"];
      tags.push(`<span class="product-lifecycle-tag ${seasonTag[0]}">${seasonTag[1]}</span>`);
      return `<span class="product-lifecycle-tags">${tags.join("")}</span>`;
    }

    function filteredProducts() {
      const query = state.productSearch;
      return (state.products?.products || []).filter((product) => {
        if (!fuzzySearchMatches(
          query,
          product.parentSku,
          product.title,
          product.brand,
          product.productType,
          product.sourceFileName,
          product.searchTerms
        )) return false;
        if (state.productTagFilter === "clearance" && !product.clearance) return false;
        if (state.productTagFilter === "spring-summer" && productSeasonType(product) !== "SPRING_SUMMER") return false;
        if (state.productTagFilter === "autumn-winter" && productSeasonType(product) !== "AUTUMN_WINTER") return false;
        if (state.productTagFilter === "all-season" && productSeasonType(product) !== "ALL_SEASON") return false;
        if (state.productTagFilter === "seasonal" && productSeasonType(product) !== "SEASONAL") return false;
        return true;
      }).sort((a, b) => compareStyleNo(a.parentSku, b.parentSku));
    }

    function renderProductCatalog() {
      if (!state.products) return;
      const filtered = filteredProducts();
      const totalPages = Math.max(1, Math.ceil(filtered.length / state.productPageSize));
      state.productPage = Math.min(state.productPage, totalPages - 1);
      const start = state.productPage * state.productPageSize;
      const pageRows = filtered.slice(start, start + state.productPageSize);
      $("#productCatalogBody").innerHTML = pageRows.map((product) => {
        const image = product.imageUrl
          ? `<img class="product-thumb" src="${escapeHtml(product.imageUrl)}" alt="${escapeHtml(product.title || product.parentSku)}" loading="lazy">`
          : `<span class="product-thumb" aria-hidden="true"></span>`;
        return `<tr>
          <td><div class="product-cell">${image}<div><span class="product-parent-sku"><span class="product-group-label">主体父款</span>${escapeHtml(product.parentSku)}</span>${productLifecycleTags(product)}</div></div></td>
          <td class="num">${integer(product.variantCount)}</td>
          <td class="num">${integer(product.colorCount)}</td>
          <td class="num">${integer(product.sizeCount)}</td>
          <td class="num">${product.price != null ? money(product.price, 2) : "—"}</td>
          <td class="num">${product.deliveryFee != null ? money(product.deliveryFee, 2) : "—"}</td>
          <td class="num">${profitText(product.profit, product.margin)}</td>
          <td class="num"><span class="cutting-yield-box" tabindex="0" title="双击编辑：每个布色一床的总做货量；0 表示淘汰款" data-product-parent-cutting="${escapeHtml(product.parentSku)}">${product.cuttingYield != null ? product.cuttingYield : "—"}</span></td>
          <td>${product.updatedAt ? new Date(product.updatedAt).toLocaleDateString("zh-CN") : "未知"}</td>
          <td><div class="table-actions"><button class="table-link" type="button" data-product-parent="${escapeHtml(product.parentSku)}">查看详情</button><button class="table-link" type="button" data-operation-parent="${escapeHtml(product.parentSku)}">记操作</button></div></td>
        </tr>`;
      }).join("") || emptyRow(10, "没有匹配的产品组", "请清除搜索条件后重试。");
      $("#productTableCount").textContent = `${filtered.length.toLocaleString("zh-CN")} 条结果，当前显示 ${pageRows.length} 条`;
      $("#productPageText").textContent = `${state.productPage + 1} / ${totalPages}`;
      $("#productPrevPage").disabled = state.productPage === 0;
      $("#productNextPage").disabled = state.productPage >= totalPages - 1;
      $$("#productTagFilters [data-product-tag-filter]").forEach((button) =>
        button.classList.toggle("active", button.dataset.productTagFilter === state.productTagFilter));
      $$("[data-product-parent]").forEach((button) => button.addEventListener("click", () => openProductDrawer(button.dataset.productParent)));
      $$("[data-operation-parent]").forEach((button) => button.addEventListener("click", () => prepareOperation("PARENT_SKU", button.dataset.operationParent)));
      $$("[data-product-parent-cutting]").forEach((box) => {
        box.addEventListener("dblclick", () => {
          if (box.querySelector("input")) return;
          const parentSku = box.dataset.productParentCutting;
          const current = box.textContent.trim();
          const seed = current === "—" ? "" : current;
          box.innerHTML = `<input class="variant-cutting-yield product-cutting-input" type="number" min="5" step="5" inputmode="numeric" value="${seed}" aria-label="每个布色的裁床数" />`;
          const input = box.querySelector("input");
          requestAnimationFrame(() => { input.focus(); input.select(); });
          const restore = (text) => { box.textContent = text; };
          const commit = async () => {
            input.removeEventListener("blur", commit);
            const raw = input.value.trim();
            const value = raw === "" ? null : Number(raw);
            if (value !== null && (!Number.isFinite(value) || value < 0 || !Number.isInteger(value))) {
              showToast("裁床数必须是非负整数；0表示淘汰款，不维护时请留空");
              restore(current);
              return;
            }
            restore(value == null ? "—" : String(value));
            try {
              const res = await fetch(`/api/products/parent/${encodeURIComponent(parentSku)}/cutting-yield`, {
                method: "PATCH",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ cuttingYield: value })
              });
              if (!res.ok) throw new Error(`HTTP ${res.status}`);
              const product = (state.products.products || []).find((item) => item.parentSku === parentSku);
              if (product) product.cuttingYield = value;
              await fetchInventoryData();
              showToast("裁床数已保存，做货建议已重新计算");
            } catch (error) {
              restore(current);
              showToast("保存失败：" + error.message);
            }
          };
          const cancel = () => {
            input.removeEventListener("blur", commit);
            restore(current);
          };
          input.addEventListener("blur", commit);
          input.addEventListener("keydown", (e) => {
            if (e.key === "Enter") { e.preventDefault(); input.blur(); }
            else if (e.key === "Escape") { e.preventDefault(); cancel(); }
          });
        });
      });
    }

    function rawFieldValue(value) {
      if (value === null || value === undefined) return "";
      if (Array.isArray(value)) return value.join(" / ");
      if (typeof value === "object") return JSON.stringify(value);
      return String(value);
    }

    function listingText(value) {
      return String(value ?? "").replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
    }

    function productColorGroups(product) {
      if (Array.isArray(product.colorGroups) && product.colorGroups.length) return product.colorGroups;
      const groups = new Map();
      (product.variants || []).forEach((variant) => {
        const color = String(variant.color || "未标注颜色").trim() || "未标注颜色";
        const key = color.toLowerCase();
        if (!groups.has(key)) {
          groups.set(key, {
            color,
            colorCategory: variant.colorCategory || "",
            pattern: variant.pattern || "",
            mainImageUrl: variant.imageUrl || "",
            swatchImageUrl: variant.swatchImageUrl || "",
            images: [],
            sizes: [],
            primaryColor: false,
            variants: [],
          });
        }
        const group = groups.get(key);
        group.variants.push(variant);
        group.primaryColor ||= Boolean(variant.primaryVariant);
        [variant.imageUrl, ...(variant.images || [])].filter(Boolean).forEach((url) => {
          if (!group.images.includes(url)) group.images.push(url);
        });
        if (variant.size && !group.sizes.includes(variant.size)) group.sizes.push(variant.size);
      });
      return [...groups.values()]
        .map((group) => ({ ...group, variantCount: group.variants.length }))
        .sort((left, right) => Number(right.primaryColor) - Number(left.primaryColor) || left.color.localeCompare(right.color));
    }

    function renderProductColorSelection(product, groups, selectedIndex) {
      const showAll = (selectedIndex == null || selectedIndex < 0 || !groups[selectedIndex]);
      const activeGroups = showAll ? groups : [groups[selectedIndex]];
      const group = showAll ? null : groups[selectedIndex];
      const images = showAll
        ? [...new Set(groups.flatMap((g) => (g.images || []).filter(Boolean)))]
        : (group.images || []).filter(Boolean);
      const hero = $("#productHeroImage");
      const heroEmpty = $("#productHeroEmpty");
      if (hero) {
        hero.hidden = !images.length;
        if (images.length) {
          hero.src = images[0];
          hero.alt = `${product.title || product.parentSku}${showAll ? "，全部颜色" : "，" + (group.color || "未标注颜色")}`;
        } else {
          hero.removeAttribute("src");
        }
      }
      if (heroEmpty) heroEmpty.hidden = Boolean(images.length);
      $("#productGallery").innerHTML = images.map((url, index) => `<button class="gallery-button ${index === 0 ? "active" : ""}" type="button" data-gallery-url="${escapeHtml(url)}" aria-label="查看第 ${index + 1} 张图片"><img src="${escapeHtml(url)}" alt="" loading="lazy"></button>`).join("");
      $("#selectedColorTitle").textContent = showAll ? "全部 SKU" : (group.color || "未标注颜色");
      const totalSku = groups.reduce((sum, g) => sum + (g.variants?.length || 0), 0);
      if (showAll) {
        $("#selectedColorMeta").textContent = `${integer(totalSku)} 个 SKU · ${integer(images.length)} 张图片 · ${integer(groups.length)} 个颜色组`;
      } else {
        $("#selectedColorMeta").textContent = `${integer(group.variantCount || group.variants?.length)} 个 SKU · ${integer(images.length)} 张图片${group.colorCategory ? ` · ${group.colorCategory}` : ""}`;
      }
      const sizes = showAll
        ? [...new Set(groups.flatMap((g) => g.sizes || []).filter(Boolean))]
        : (group.sizes || []);
      $("#selectedColorSizes").innerHTML = sizes.map((size) => `<span class="apparel-size-chip">${escapeHtml(size)}</span>`).join("") || `<span class="apparel-size-chip">未标注尺码</span>`;
      const colorCol = showAll ? `<th>颜色</th>` : "";
      const head = `<thead><tr>${colorCol}<th>尺码</th><th>SKU</th><th>Item ID</th><th>UPC</th><th class="num">价格</th><th class="num">配送重量(LB)</th><th class="num">预估配送费</th><th class="num">测试毛利/毛利率</th><th>图案 / 材质</th><th>更新时间</th><th>操作</th></tr></thead>`;
      const rows = activeGroups.flatMap((g) => (g.variants || []).map((variant) => {
        const colorCell = showAll ? `<td>${escapeHtml(g.color || "未标注颜色")}</td>` : "";
        return `<tr>${colorCell}
          <td><span class="variant-sku">${escapeHtml(variant.size || "未标注")}</span>${variant.primaryVariant ? `<span class="sub-id">主变体</span>` : ""}</td>
          <td><span class="variant-sku">${escapeHtml(variant.sku || "")}</span></td>
          <td>${escapeHtml(variant.itemId || "—")}</td>
          <td>${escapeHtml(variant.upc || "—")}</td>
          <td class="num">${variant.price != null ? money(variant.price, 2) : "—"}</td>
          <td class="num">${variant.shippingWeightLb != null ? Number(variant.shippingWeightLb).toFixed(2) : "—"}${variant.shippingWeightUnit ? " " + escapeHtml(variant.shippingWeightUnit) : ""}</td>
          <td class="num">${variant.deliveryFee != null ? money(variant.deliveryFee, 2) : "—"}</td>
          <td class="num">${profitText(variant.profit, variant.margin)}</td>
          <td>${escapeHtml([variant.pattern, variant.material].filter(Boolean).join(" · ") || "—")}</td>
          <td>${variant.updatedAt ? new Date(variant.updatedAt).toLocaleDateString("zh-CN") : "—"}</td>
          <td><button class="table-link" type="button" data-operation-sku="${escapeHtml(variant.sku || "")}">记操作</button></td>
        </tr>`;
      })).join("") || emptyRow(showAll ? 12 : 11, showAll ? "这款还没有 SKU" : "当前颜色没有 SKU", "请核对上传表的颜色与尺码字段。");
      const tableWrap = $("#selectedColorTable");
      if (tableWrap) tableWrap.innerHTML = `<table class="variant-table">${head}<tbody>${rows}</tbody></table>`;
      $$("#productColorGroups [data-color-index]").forEach((button) => {
        const active = !showAll && Number(button.dataset.colorIndex) === selectedIndex;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
      });
      $$("#productGallery [data-gallery-url]").forEach((button) => button.addEventListener("click", () => {
        if (hero) {
          hero.src = button.dataset.galleryUrl;
          hero.hidden = false;
        }
        $$("#productGallery [data-gallery-url]").forEach((item) => item.classList.toggle("active", item === button));
      }));
      $$("#selectedColorTable [data-operation-sku]").forEach((button) => button.addEventListener("click", () => {
        $("#productDrawer").close();
        prepareOperation("SKU", button.dataset.operationSku);
      }));
    }

    function renderProductDetail(product) {
      const groups = productColorGroups(product);
      const initialIndex = Math.max(0, groups.findIndex((group) => group.primaryColor));
      const initialGroup = groups[initialIndex] || {};
      const heroImage = initialGroup.images?.[0] || initialGroup.mainImageUrl || product.mainImageUrl;
      const attributes = Object.entries(product.attributes || {}).map(([label, value]) => `<div class="attribute-item"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`).join("");
      const rawFields = Object.entries(product.rawFields || {})
        .filter(([, value]) => rawFieldValue(value).trim())
        .map(([key, value]) => `<div class="raw-field"><span>${escapeHtml(key)}</span><strong>${escapeHtml(rawFieldValue(value))}</strong></div>`)
        .join("");
      const features = (product.keyFeatures || []).map((feature) => `<li>${escapeHtml(listingText(feature))}</li>`).join("");
      const sizes = [...new Set(groups.flatMap((group) => group.sizes || []).filter(Boolean))];
      const assumption = product.profitAssumption || {};
      const profitSummary = (product.variants || []).find((variant) => variant.profit != null) || null;
      const colorButtons = groups.map((group, index) => {
        const image = group.swatchImageUrl || group.mainImageUrl || group.images?.[0];
        return `<button class="color-group-button ${index === initialIndex ? "active" : ""}" type="button" data-color-index="${index}" aria-pressed="${index === initialIndex}" aria-label="查看 ${escapeHtml(group.color || "未标注颜色")} 颜色组">
          ${image ? `<img class="color-group-thumb" src="${escapeHtml(image)}" alt="" loading="lazy">` : `<span class="color-group-thumb" aria-hidden="true"></span>`}
          <span class="color-group-copy"><strong>${escapeHtml(group.color || "未标注颜色")}</strong><small>${integer(group.variantCount || group.variants?.length)} 个 SKU · ${integer(group.images?.length)} 张图</small></span>
          ${group.primaryColor ? `<span class="primary-color-marker">主体色</span>` : ""}
        </button>`;
      }).join("");
      $("#productDrawerTitle").textContent = product.title || product.parentSku;
      $("#productDrawerSku").textContent = `${product.parentSku} · ${groups.length} 个颜色组 · ${product.variantCount} 个 SKU`;
      $("#productDrawerBody").innerHTML = `
        <div class="product-overview">
          <div class="product-visual">
            <div class="product-subject-label"><span>产品组主体</span><strong>${escapeHtml(product.parentSku)}</strong></div>
            <img class="product-hero-image" id="productHeroImage" src="${heroImage ? escapeHtml(heroImage) : ""}" alt="${escapeHtml(product.title || product.parentSku)}" ${heroImage ? "" : "hidden"}>
            <div class="product-hero-image empty-state" id="productHeroEmpty" ${heroImage ? "hidden" : ""}><strong>当前颜色暂无图片</strong></div>
            <div class="product-gallery" id="productGallery"></div>
          </div>
          <div class="product-listing">
            <div class="product-listing-meta"><span class="status-badge scale">主体父款</span><span class="status-badge scale">${escapeHtml(product.brand || "未填写品牌")}</span><span class="status-badge collect">${escapeHtml(product.productType || "未分类")}</span>${productLifecycleTags(product)}</div>
            <h3>${escapeHtml(product.title || "未填写 Listing 标题")}</h3>
            <ul class="feature-list">${features || `<li>未填写 Key Features。</li>`}</ul>
            <p class="product-description">${escapeHtml(listingText(product.shortDescription) || "未填写商品描述。")}</p>
            <div class="product-structure-stats">
              <div class="product-structure-stat"><span>颜色组</span><strong>${integer(groups.length)}</strong></div>
              <div class="product-structure-stat"><span>尺码规格</span><strong>${integer(sizes.length)}</strong></div>
              <div class="product-structure-stat"><span>SKU 变体</span><strong>${integer(product.variantCount)}</strong></div>
            </div>
          </div>
        </div>
        <section class="product-detail-section">
          <div class="apparel-section-header"><div><h3>经营标签</h3><p>清货款会停止国内仓发货和工厂做货；春夏、秋冬和四季款用于经营筛选，不会强制覆盖模型预测。</p></div><span class="apparel-section-meta">父款生效</span></div>
          <div class="product-tag-editor">
            <fieldset class="product-tag-options"><legend>经营标签</legend>
              <label class="product-tag-option"><input id="productTagClearance" type="checkbox" ${product.clearance ? "checked" : ""}><span><strong>清货款</strong><small>停止补货，保留库存和销量数据用于清库存决策。</small></span></label>
              <div class="product-tag-option product-season-option"><label for="productSeasonType">季节属性</label><select id="productSeasonType"><option value="SEASONAL"${productSeasonType(product) === "SEASONAL" ? " selected" : ""}>季节待标</option><option value="SPRING_SUMMER"${productSeasonType(product) === "SPRING_SUMMER" ? " selected" : ""}>春夏款</option><option value="AUTUMN_WINTER"${productSeasonType(product) === "AUTUMN_WINTER" ? " selected" : ""}>秋冬款</option><option value="ALL_SEASON"${productSeasonType(product) === "ALL_SEASON" ? " selected" : ""}>四季款</option></select><small>选择经营季节后，产品库可按标签筛选。</small></div>
            </fieldset>
            <div class="product-tag-actions"><span class="product-tag-save-status" id="productTagSaveStatus" role="status" aria-live="polite"></span><button class="primary-button" id="saveProductTags" type="button">保存经营标签</button></div>
          </div>
        </section>
        <section class="product-detail-section">
          <div class="apparel-section-header"><div><h3>颜色、图片与尺码</h3><p>点击颜色查看该颜色的 SKU；再次点击已选中的颜色可取消选择，直接显示该款全部 SKU。</p></div><span class="apparel-section-meta">${integer(groups.length)} COLOR GROUPS</span></div>
          <div class="color-group-list" id="productColorGroups">${colorButtons || `<div class="empty-state"><strong>暂无颜色组</strong>请核对上传表中的颜色字段。</div>`}</div>
          <div class="color-group-panel">
            <div class="color-group-toolbar">
              <div class="color-group-title"><strong id="selectedColorTitle"></strong><span id="selectedColorMeta"></span></div>
              <div class="size-chip-list" id="selectedColorSizes"></div>
            </div>
            <div class="table-scroll" id="selectedColorTable"></div>
          </div>
        </section>
        <section class="product-detail-section">
          <div class="apparel-section-header"><div><h3>毛利测算假设</h3><p>按款录入采购成本、头程、销量、广告费；佣金/仓储按档位公式自动计算，汇率默认 7。</p></div><span class="apparel-section-meta">${profitText(profitSummary?.profit, profitSummary?.margin)}</span></div>
          <div class="form-grid profit-form-grid">
            <div class="field"><label for="profitPurchaseCost">采购成本(¥)</label><div class="input-wrap"><input id="profitPurchaseCost" type="number" min="0" step="0.01" value="${assumption.purchaseCost ?? ""}" placeholder="必填"><span class="input-suffix">¥</span></div></div>
            <div class="field"><label for="profitFirstLeg">头程(¥)</label><div class="input-wrap"><input id="profitFirstLeg" type="number" min="0" step="0.01" value="${assumption.firstLeg ?? ""}" placeholder="必填"><span class="input-suffix">¥</span></div></div>
            <div class="field"><label for="profitSalesQty">销量</label><div class="input-wrap"><input id="profitSalesQty" type="number" min="0" step="1" value="${assumption.salesQty ?? ""}" placeholder="必填"><span class="input-suffix">件</span></div></div>
            <div class="field"><label for="profitAdCost">总广告费(原币)</label><div class="input-wrap"><input id="profitAdCost" type="number" min="0" step="0.01" value="${assumption.adCost ?? 1}" placeholder="默认 1"><span class="input-suffix">$</span></div></div>
            <div class="field"><label for="profitRefundRate">退款率(%)手动</label><div class="input-wrap"><input id="profitRefundRate" type="number" min="0" max="100" step="0.01" value="${assumption.manualRefundRate != null ? (Number(assumption.manualRefundRate) * 100).toFixed(2) : ""}" placeholder="可选"><span class="input-suffix">%</span></div></div>
          </div>
          <p class="form-note profit-refund-note">当前生效退款率：<strong>${product.refundRate != null ? (Number(product.refundRate) * 100).toFixed(2) + "%" : "—"}</strong> · 来源：<strong>${refundSourceText(product.refundRateSource)}</strong>${product.refundRateSource === "HISTORY" && assumption.manualRefundRate != null ? `。已手动填写 ${(Number(assumption.manualRefundRate) * 100).toFixed(2)}%，但系统历史退款率优先生效；仅当该款无历史退款数据时才回退到手填值。` : product.refundRateSource === "DEFAULT" ? "。该款暂无系统历史退款数据，也未手动填写，按 0 计算。" : ""}</p>
          <div class="profit-form-actions"><span class="profit-save-status" id="profitSaveStatus"></span><button class="primary-button" id="saveProfitAssumption" type="button">保存并重新测算</button></div>
        </section>
        <section class="product-detail-section"><h3>产品属性</h3><div class="attribute-grid">${attributes || `<div class="empty-state"><strong>暂无属性</strong></div>`}</div></section>
        <section class="product-detail-section"><details class="raw-fields"><summary>查看上传表原始字段（${Object.keys(product.rawFields || {}).length}）</summary><div class="raw-field-grid">${rawFields}</div></details></section>`;
      let currentColorIndex = initialIndex;
      $$("#productColorGroups [data-color-index]").forEach((button) => button.addEventListener("click", () => {
        const index = Number(button.dataset.colorIndex);
        currentColorIndex = (currentColorIndex === index) ? null : index;
        renderProductColorSelection(product, groups, currentColorIndex);
      }));
      renderProductColorSelection(product, groups, currentColorIndex);
      const saveProductTagsButton = $("#saveProductTags");
      if (saveProductTagsButton) saveProductTagsButton.addEventListener("click", async () => {
        const status = $("#productTagSaveStatus");
        const clearance = $("#productTagClearance").checked;
        const seasonType = $("#productSeasonType").value;
        saveProductTagsButton.disabled = true;
        status.textContent = "正在保存标签并刷新做货建议…";
        try {
          const response = await fetch(`/api/forecast/lifecycle/${encodeURIComponent(product.parentSku)}`, {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ clearance, seasonType })
          });
          const payload = await response.json();
          if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
          product.clearance = Boolean(payload.data?.clearance);
          product.allSeason = Boolean(payload.data?.allSeason);
          product.seasonType = payload.data?.seasonType || seasonType;
          await Promise.all([loadProducts(), fetchInventoryData(), loadDemandForecast()]);
          await openProductDrawer(product.parentSku);
          showToast(clearance ? "已标记为清货款，做货建议已停止" : "经营标签已保存，做货建议已刷新");
        } catch (error) {
          status.textContent = `保存失败：${error.message}`;
          saveProductTagsButton.disabled = false;
        }
      });
      const saveButton = $("#saveProfitAssumption");
      if (saveButton) saveButton.addEventListener("click", async () => {
        const readNumber = (id) => {
          const raw = $(id).value.trim();
          if (!raw) return null;
          const value = Number(raw);
          return Number.isFinite(value) ? value : null;
        };
        const status = $("#profitSaveStatus");
        const refundPercent = readNumber("#profitRefundRate");
        const body = {
          parentSku: product.parentSku,
          purchaseCost: readNumber("#profitPurchaseCost"),
          firstLeg: readNumber("#profitFirstLeg"),
          salesQty: readNumber("#profitSalesQty"),
          adCost: readNumber("#profitAdCost") ?? 1,
          refundRate: refundPercent == null ? null : refundPercent / 100,
        };
        saveButton.disabled = true;
        status.textContent = "正在保存并重新测算…";
        try {
          const response = await fetch("/api/products/profit-assumption", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          });
          const payload = await response.json();
          if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
          status.textContent = "已保存，正在刷新…";
          await openProductDrawer(product.parentSku);
          loadProducts();
        } catch (error) {
          status.textContent = `保存失败：${error.message}`;
          saveButton.disabled = false;
        }
      });
    }

    async function openProductDrawer(parentSku) {
      const drawer = $("#productDrawer");
      $("#productDrawerTitle").textContent = "正在读取产品详情";
      $("#productDrawerSku").textContent = parentSku;
      $("#productDrawerBody").innerHTML = `<div class="empty-state"><strong>正在解析 Listing 与图片</strong>请稍候。</div>`;
      if (!drawer.open) drawer.showModal();
      try {
        const response = await fetch(`/api/products/${encodeURIComponent(parentSku)}`);
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        renderProductDetail(payload.data);
      } catch (error) {
        $("#productDrawerBody").innerHTML = `<div class="empty-state"><strong>产品详情读取失败</strong>${escapeHtml(error.message)}</div>`;
      }
    }

    async function loadProducts() {
      if (state.productsLoading) return;
      state.productsLoading = true;
      $("#productCatalogBody").innerHTML = emptyRow(9, "正在读取产品上传表", "请稍候。");
      try {
        const response = await fetch("/api/products/catalog");
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        state.products = payload.data;
        const meta = payload.data.meta;
        $("#productParentCount").textContent = integer(meta.parentProductCount);
        $("#productStatusHeadline").textContent = "图片与 Listing 已完成结构化";
        $("#productStatusDetail").textContent = `${integer(meta.sourceFileCount)} 个上传文件，${integer(meta.variantCount)} 个 SKU 变体；Listing、图片与服装属性保留在 MySQL。`;
        $("#productUpdatedAt").textContent = meta.latestUpdatedAt ? `更新 ${new Date(meta.latestUpdatedAt).toLocaleString("zh-CN")}` : "暂无更新时间";
        renderProductMetrics(meta);
        renderProductCatalog();
        updatePageMeta();
        if (state.view === "demand-forecast") renderDemandForecast();
        if (state.view === "inventory" && state.inventory) renderInventoryByStyle(state.inventory.rows);
        if (state.view === "sales" && state.sales) renderSales();
        if (state.view === "recent") {
          if (state.recent) renderRecent();
          if (state.styleSales) renderStyleSales();
          if (state.adProducts) renderAdProducts();
        }
      } catch (error) {
        $("#productStatusHeadline").textContent = "产品资料读取失败";
        $("#productStatusDetail").textContent = error.message;
        $("#productCatalogBody").innerHTML = emptyRow(9, "产品资料读取失败", escapeHtml(error.message));
      } finally {
        state.productsLoading = false;
      }
    }

    async function loadInventory() {
      if (state.inventoryLoading) return;
      state.inventoryLoading = true;
      $("#inventoryByStyleBody").innerHTML = emptyRow(11, "正在读取库存与销量", "请稍候。");
      try {
        await fetchInventoryData();
        renderInventory();
      } catch (error) {
        $("#inventoryStatusHeadline").textContent = "库存读取失败";
        $("#inventoryStatusDetail").textContent = error.message;
        $("#inventoryByStyleBody").innerHTML = emptyRow(11, "库存读取失败", escapeHtml(error.message));
      } finally {
        state.inventoryLoading = false;
      }
    }

    async function fetchInventoryData() {
      const response = await fetch(`/api/inventory/data?${analysisQuery({ targetDays: String(state.inventoryTargetDays) })}`);
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
      state.inventory = payload.data;
      return payload.data;
    }

    async function loadDemandForecast() {
      const body = $("#forecastWorkbenchBody");
      if (body) body.innerHTML = forecastSkeletonRows();
      try {
        if (!state.inventory || !state.inventory.rows) {
          await fetchInventoryData();
        }
        $("#forecastTargetDays").value = state.inventoryTargetDays;
        const [fResp, pResp, dResp, planResp] = await Promise.all([
          fetch("/api/forecast/factors"),
          fetch("/api/forecast/parent-daily-sales"),
          fetch("/api/forecast/decisions"),
          fetch("/api/forecast/recommendations")
        ]);
        const fPayload = await fResp.json();
        if (!fResp.ok || !fPayload.success) throw new Error(fPayload.message || `HTTP ${fResp.status}`);
        state.forecastFactors = fPayload.data || [];
        const pPayload = await pResp.json();
        if (!pResp.ok || !pPayload.success) throw new Error(pPayload.message || `HTTP ${pResp.status}`);
        state.parentDailySales = pPayload.data || [];
        const dPayload = await dResp.json();
        if (!dResp.ok || !dPayload.success) throw new Error(dPayload.message || `HTTP ${dResp.status}`);
        state.replenishmentDecisions = dPayload.data || [];
        const planPayload = await planResp.json();
        if (!planResp.ok || !planPayload.success) throw new Error(planPayload.message || `HTTP ${planResp.status}`);
        state.replenishmentPlan = planPayload.data || null;
        wireForecastWorkbench();
        renderDemandForecast();
      } catch (error) {
        const b = $("#forecastWorkbenchBody");
        if (b) b.innerHTML = emptyRow(8, "需求预测读取失败", `请刷新或重新导入数据后重试：${escapeHtml(error.message)}`);
      }
    }

    function forecastSkeletonRows() {
      return Array.from({ length: 6 }, () => `<tr>${Array.from({ length: 8 }, (_, index) =>
        `<td><div class="forecast-skeleton" style="width:${index === 1 ? 82 : 58}%">读取中</div></td>`
      ).join("")}</tr>`).join("");
    }

    function wireForecastWorkbench() {
      const body = $("#forecastWorkbenchBody");
      if (!body || body.dataset.wired) return;
      body.dataset.wired = "1";
      body.addEventListener("dblclick", (e) => {
        const cell = e.target.closest(".factor-cell");
        if (cell) activateFactorCell(cell);
      });
      body.addEventListener("keydown", (e) => {
        const cell = e.target.closest(".factor-cell");
        if (cell && (e.key === "Enter" || e.key === " ")) {
          e.preventDefault();
          activateFactorCell(cell);
        }
      });
      body.addEventListener("click", (e) => {
        const adoptButton = e.target.closest("[data-adopt-forecast-suggestion]");
        if (adoptButton) {
          const editor = adoptButton.closest("[data-forecast-decision-editor]");
          const input = editor?.querySelector("[data-decision-qty]");
          if (input) {
            input.value = adoptButton.dataset.adoptForecastSuggestion;
            input.focus();
            input.select();
          }
          return;
        }
        const saveButton = e.target.closest("[data-save-forecast-decision]");
        if (saveButton) {
          saveReplenishmentDecision(saveButton.closest("[data-forecast-decision-editor]"), saveButton);
          return;
        }
        const openButton = e.target.closest("[data-open-forecast-decision]");
        if (openButton) {
          const parentSku = openButton.dataset.openForecastDecision;
          state.forecastWorkbenchExpanded.add(parentSku);
          renderDemandForecast();
          requestAnimationFrame(() => {
            const target = [...document.querySelectorAll("[data-decision-qty]")]
              .find((input) => input.dataset.decisionQty === parentSku);
            target?.focus();
          });
        }
      });
    }

    function forecastFactorMap() {
      const map = new Map();
      (state.forecastFactors || []).forEach((f) => map.set(f.sku, f));
      return map;
    }

    function replenishmentDecisionMap() {
      const map = new Map();
      (state.replenishmentDecisions || []).forEach((decision) => map.set(decision.parentSku, decision));
      return map;
    }

    function replenishmentPlanMap() {
      const map = new Map();
      (state.replenishmentPlan?.recommendations || []).forEach((plan) => map.set(plan.sku, plan));
      return map;
    }

    function earliestForecastDate(current, candidate) {
      if (!candidate) return current;
      return !current || String(candidate) < String(current) ? candidate : current;
    }

    function forecastDate(value) {
      return value ? String(value).slice(0, 10) : "-";
    }

    function forecastStockoutVariant(items, dateField, date) {
      if (!date) return null;
      return items
        .filter((item) => item.plan?.[dateField] === date)
        .sort((left, right) => String(left.row?.size || left.sku).localeCompare(String(right.row?.size || right.sku)) || String(left.sku).localeCompare(String(right.sku)))[0] || null;
    }

    function forecastStockoutSize(item) {
      return item?.row?.size || item?.sku || "-";
    }

    function forecastStockoutLabel(date, item) {
      return item ? `${forecastDate(date)} · ${forecastStockoutSize(item)}` : forecastDate(date);
    }

    function forecastHistoryByParent() {
      const result = new Map();
      const latestText = String(DATA.meta?.latestDate || "").slice(0, 10);
      const latest = latestText ? new Date(`${latestText}T00:00:00`) : new Date();
      (DATA.skuDaily || []).forEach((row) => {
        const parentSku = row.sku;
        if (!parentSku) return;
        const dayText = String(row.date || "").slice(0, 10);
        const day = dayText ? new Date(`${dayText}T00:00:00`) : null;
        if (!day || Number.isNaN(day.getTime())) return;
        const age = Math.round((latest.getTime() - day.getTime()) / 86400000);
        if (age < 0 || age > 29) return;
        if (!result.has(parentSku)) {
          result.set(parentSku, { sales7: 0, previous7: 0, sales30: 0, daily14: Array(14).fill(0) });
        }
        const history = result.get(parentSku);
        const units = number(row.orders);
        history.sales30 += units;
        if (age <= 6) history.sales7 += units;
        else if (age <= 13) history.previous7 += units;
        if (age <= 13) history.daily14[13 - age] += units;
      });
      result.forEach((history) => {
        history.trendPct = history.previous7 > 0
          ? (history.sales7 - history.previous7) / history.previous7
          : history.sales7 > 0 ? null : 0;
      });
      return result;
    }

    function forecastRiskRank(risk) {
      return ({ OUT_OF_STOCK: 0, URGENT: 1, LOW: 2, WATCH: 3, REPLENISH: 4, OVERSTOCK: 5, NO_DEMAND: 6, HEALTHY: 7 })[risk] ?? 8;
    }

    function forecastInventoryCompleteness(row) {
      return [row.publishingStatus, row.itemLifecycle, row.itemName, row.imageUrl, row.status]
        .filter((value) => value != null && String(value).trim() !== "").length;
    }

    function buildForecastWorkbench() {
      const sourceRows = (state.inventory && state.inventory.rows) || [];
      const productParents = new Set((state.products?.products || [])
        .map((product) => product.parentSku)
        .filter(Boolean));
      const uniqueBySku = new Map();
      sourceRows
        .filter((row) => productParents.size === 0 || productParents.has(row.parentSku))
        .forEach((row) => {
        const key = row.sku || `missing-${uniqueBySku.size}`;
        const existing = uniqueBySku.get(key);
        if (!existing || forecastInventoryCompleteness(row) > forecastInventoryCompleteness(existing)) {
          uniqueBySku.set(key, row);
        }
      });
      const rows = [...uniqueBySku.values()];
      state.forecastWorkbenchDuplicateRows = Math.max(0, sourceRows.length - rows.length);
      const factors = forecastFactorMap();
      const decisions = replenishmentDecisionMap();
      const plans = replenishmentPlanMap();
      const historyByParent = forecastHistoryByParent();
      const byStyle = new Map();
      for (const r of rows) {
        const fc = number(r.forecastSuggestedUnits);
        const productionFc = number(r.productionSuggestedUnits);
        const parent = r.parentSku || "（未分组）";
        if (!byStyle.has(parent)) {
          byStyle.set(parent, {
            parentSku: parent, items: [], totalFc: 0, totalProductionSuggested: 0, totalPlan: 0,
            totalAvailable: 0, totalReserved: 0, totalTransit: 0, totalProduction: 0,
            totalP90Demand: 0, totalDomesticAvailable: 0, totalDomesticShipment: 0, totalWip: 0,
            expectedStockoutDate: null, highRiskStockoutDate: null, forecastModels: new Set(),
            totalRecent: 0, totalForecastDaily: 0, oosCount: 0, risk: "HEALTHY", riskRank: 8,
            itemName: null, imageUrl: null, customFactorCount: 0,
            productionConfigIssues: 0,
            seasonal: null, gainDays: null, safetyDays: null, returnRate: null, reuseRate: null,
            manualDailySales: null
          });
        }
        const g = byStyle.get(parent);
        const f = factors.get(r.sku);
        const plan = plans.get(r.sku) || null;
        g.items.push({ sku: r.sku || "-", fc, productionFc, factor: f, plan, row: r });
        g.totalFc += fc;
        g.totalProductionSuggested += productionFc;
        g.totalPlan += f && f.demandPlan != null ? number(f.demandPlan) : 0;
        g.totalAvailable += number(r.availableUnits);
        g.totalReserved += number(r.reservedUnits);
        g.totalTransit += number(r.transitUnits);
        g.totalProduction += number(r.productionUnits);
        g.totalRecent += number(r.recentUnits);
        g.totalForecastDaily += number(r.forecastDailyUnits);
        if (plan) {
          g.totalP90Demand += number(plan.p90DemandUnits);
          g.totalDomesticAvailable += number(plan.domesticAvailableUnits);
          g.totalDomesticShipment += number(plan.domesticShipmentUnits);
          g.totalWip += number(plan.wipUnits);
          g.expectedStockoutDate = earliestForecastDate(g.expectedStockoutDate, plan.expectedStockoutDate);
          g.highRiskStockoutDate = earliestForecastDate(g.highRiskStockoutDate, plan.highRiskStockoutDate);
          if (plan.forecastModel) g.forecastModels.add(plan.forecastModel);
        }
        if (number(r.availableUnits) <= 0) g.oosCount += 1;
        if (fc > 0 && !["ALLOCATED", "PLANNED", "PLANNED_LATE", "PARTIALLY_COVERED", "BED_NOT_COVERED", "MANUAL_REVIEW", "NOT_NEEDED", "CLEARANCE", "RETIRED", "NEW_NO_HISTORY"].includes(r.productionSuggestionStatus)) {
          g.productionConfigIssues += 1;
        }
        if (!g.itemName && r.itemName) g.itemName = r.itemName;
        if (!g.imageUrl && r.imageUrl) g.imageUrl = r.imageUrl;
        if (f) {
          if ([f.seasonalCoef, f.gainDays, f.safetyDays, f.returnRate, f.returnReuseRate].some((value) => value != null)) {
            g.customFactorCount += 1;
          }
          if (g.seasonal == null && f.seasonalCoef != null) g.seasonal = f.seasonalCoef;
          if (g.gainDays == null && f.gainDays != null) g.gainDays = f.gainDays;
          if (g.safetyDays == null && f.safetyDays != null) g.safetyDays = f.safetyDays;
          if (g.returnRate == null && f.returnRate != null) g.returnRate = f.returnRate;
          if (g.reuseRate == null && f.returnReuseRate != null) g.reuseRate = f.returnReuseRate;
        }
        if (g.manualDailySales == null && state.parentDailySales && state.parentDailySales[parent] != null) {
          g.manualDailySales = state.parentDailySales[parent];
        }
      }
      return [...byStyle.values()]
        .map((g) => {
          const history = historyByParent.get(g.parentSku) || {
            sales7: 0, previous7: 0, sales30: 0, trendPct: 0, daily14: Array(14).fill(0)
          };
          const totalSupply = g.totalAvailable + g.totalTransit + g.totalProduction;
          const projectedDays = g.totalForecastDaily > 0 ? totalSupply / g.totalForecastDaily : null;
          const systemRisk = inventoryStyleSystemRisk(
            g.totalAvailable,
            totalSupply,
            g.totalForecastDaily,
            g.totalFc
          );
          const decision = decisions.get(g.parentSku) || null;
          const mismatchBase = Math.max(g.totalProductionSuggested, g.totalPlan, 1);
          const expectedStockoutVariant = forecastStockoutVariant(g.items, "expectedStockoutDate", g.expectedStockoutDate);
          const highRiskStockoutVariant = forecastStockoutVariant(g.items, "highRiskStockoutDate", g.highRiskStockoutDate);
          return {
            ...g,
            risk: systemRisk,
            riskRank: forecastRiskRank(systemRisk),
            skuCount: g.items.length,
            history,
            totalSupply,
            projectedDays,
            decision,
            expectedStockoutVariant,
            highRiskStockoutVariant,
            planMismatch: g.totalPlan > 0 && Math.abs(g.totalProductionSuggested - g.totalPlan) / mismatchBase >= .25,
            needsReview: !decision || decision.status !== "CONFIRMED"
          };
        });
    }

    function factorCellText(v) {
      return v == null ? '<span class="cell-empty">—</span>' : String(v);
    }

    function forecastSparkline(values) {
      const safeValues = values?.length ? values : Array(14).fill(0);
      const width = 68;
      const height = 30;
      const max = Math.max(...safeValues, 1);
      const points = safeValues.map((value, index) => {
        const x = 2 + index * ((width - 4) / Math.max(1, safeValues.length - 1));
        const y = height - 3 - (number(value) / max) * (height - 7);
        return `${x.toFixed(2)},${y.toFixed(2)}`;
      }).join(" ");
      return `<svg class="forecast-sparkline" viewBox="0 0 ${width} ${height}" aria-hidden="true"><line class="forecast-sparkline-base" x1="2" y1="${height - 2}" x2="${width - 2}" y2="${height - 2}"/><polyline class="forecast-sparkline-line" points="${points}"/></svg>`;
    }

    function forecastTrendText(history) {
      if (history.trendPct == null) return { text: "前期无销量", className: "positive" };
      const value = number(history.trendPct);
      if (Math.abs(value) < .005) return { text: "较前7天持平", className: "" };
      return {
        text: `较前7天${value > 0 ? "+" : ""}${(value * 100).toFixed(0)}%`,
        className: value > 0 ? "positive" : "danger"
      };
    }

    function forecastDecisionMeta(status) {
      return ({
        CONFIRMED: { label: "已确认", className: "confirmed" },
        HOLD: { label: "暂缓", className: "warning" },
        DRAFT: { label: "待确认", className: "" }
      })[status] || { label: "待填写", className: "" };
    }

    function filteredForecastStyles(styles) {
      const query = state.forecastWorkbenchSearch.trim().toLowerCase();
      const filtered = styles.filter((style) => {
        const matchesSearch = !query || [style.parentSku, style.itemName, ...style.items.map((item) => item.sku)]
          .some((value) => String(value || "").toLowerCase().includes(query));
        if (!matchesSearch) return false;
        if (state.forecastWorkbenchFilter === "replenish") return style.totalFc > 0 || style.totalProductionSuggested > 0;
        if (state.forecastWorkbenchFilter === "risk") return style.riskRank <= 2 || style.oosCount > 0;
        if (state.forecastWorkbenchFilter === "mismatch") return style.planMismatch;
        if (state.forecastWorkbenchFilter === "unconfirmed") return style.needsReview;
        return true;
      });
      return filtered.sort((a, b) => {
        if (state.forecastWorkbenchSort === "style") return compareStyleNo(a.parentSku, b.parentSku);
        if (state.forecastWorkbenchSort === "suggested") return b.totalProductionSuggested - a.totalProductionSuggested || b.totalFc - a.totalFc || compareStyleNo(a.parentSku, b.parentSku);
        if (state.forecastWorkbenchSort === "sales") return b.history.sales30 - a.history.sales30 || compareStyleNo(a.parentSku, b.parentSku);
        if (state.forecastWorkbenchSort === "coverage") {
          return (a.projectedDays ?? Number.MAX_SAFE_INTEGER) - (b.projectedDays ?? Number.MAX_SAFE_INTEGER)
            || b.totalFc - a.totalFc || compareStyleNo(a.parentSku, b.parentSku);
        }
        return a.riskRank - b.riskRank || b.totalProductionSuggested - a.totalProductionSuggested || b.totalFc - a.totalFc || b.history.sales30 - a.history.sales30 || compareStyleNo(a.parentSku, b.parentSku);
      });
    }

    function renderDemandForecast() {
      const body = $("#forecastWorkbenchBody");
      if (!body) return;
      if (!state.inventory || !state.inventory.rows) {
        body.innerHTML = emptyRow(11, "还没有库存数据", "请先导入最新库存和订单数据，再重新打开需求预测工作台。");
        return;
      }
      const allStyles = buildForecastWorkbench();
      const styles = filteredForecastStyles(allStyles);
      const totalFc = allStyles.reduce((sum, style) => sum + style.totalFc, 0);
      const totalProductionSuggested = allStyles.reduce((sum, style) => sum + style.totalProductionSuggested, 0);
      const totalP90Demand = allStyles.reduce((sum, style) => sum + style.totalP90Demand, 0);
      const totalDomesticShipment = allStyles.reduce((sum, style) => sum + style.totalDomesticShipment, 0);
      const needReplenish = allStyles.filter((style) => style.totalFc > 0).length;
      const productionConfigStyles = allStyles.filter((style) => style.productionConfigIssues > 0).length;
      const riskStyles = allStyles.filter((style) => style.riskRank <= 2 || style.oosCount > 0).length;
      const mismatchStyles = allStyles.filter((style) => style.planMismatch).length;
      const pendingStyles = allStyles.filter((style) => style.needsReview).length;
      const planSummary = state.replenishmentPlan?.summary || {};
      const confirmedTotal = allStyles.reduce((sum, style) =>
        sum + (style.decision?.status === "CONFIRMED" ? number(style.decision.finalQuantity) : 0), 0);
      $("#forecastWorkbenchMetrics").innerHTML = [
        ["P90 60天预测需求", integer(totalP90Demand), "父款预测后按近90天销量分配到颜色尺码 SKU", "forecast"],
        ["国内仓发货建议", integer(totalDomesticShipment), "仅发未来30天到货后仍需要的数量，不会自动扣减国内仓", totalDomesticShipment ? "forecast" : ""],
        ["工厂做货建议", integer(totalProductionSuggested), `国内仓和60天内做货中库存抵减后，${needReplenish} 个款式仍需补货`, "forecast"],
        ["预计先断货 SKU", integer(planSummary.expectedStockoutSkus || 0), "按 P50 需求判断，国内仓有货的 SKU 也会提示", "attention"],
        ["P90 高风险 SKU", integer(planSummary.highRiskStockoutSkus || 0), "按90%服务水平识别未来60天内的高风险断货", "attention"],
        ["待人工确认", integer(pendingStyles), `已确认 ${integer(confirmedTotal)}；裁床资料待维护 ${integer(productionConfigStyles)}`, productionConfigStyles ? "attention" : ""]
      ].map(([label, value, detail, cls]) =>
        `<article class="inventory-metric ${cls}"><span>${label}</span><strong>${value}</strong><small>${detail}</small></article>`
      ).join("");
      $("#forecastWorkbenchStyleCount").textContent = integer(needReplenish);
      $("#forecastTargetDays").value = state.inventoryTargetDays;
      $("#forecastDecisionSignals").innerHTML = `
        <button class="forecast-signal critical" type="button" data-forecast-filter="risk"><span class="forecast-signal-icon">!</span><span><strong>先处理库存风险</strong><small>缺货与 14 天内风险款式需要先核对核心尺码</small></span><span class="forecast-signal-count">${integer(riskStyles)}</span></button>
        <button class="forecast-signal warning" type="button" data-forecast-filter="mismatch"><span class="forecast-signal-icon">≠</span><span><strong>核对计划差异</strong><small>表单计划和系统建议相差达到 25%</small></span><span class="forecast-signal-count">${integer(mismatchStyles)}</span></button>
        <button class="forecast-signal" type="button" data-forecast-filter="unconfirmed"><span class="forecast-signal-icon">✓</span><span><strong>完成最终决策</strong><small>填写人工数量、状态和判断依据</small></span><span class="forecast-signal-count">${integer(pendingStyles)}</span></button>
        ${state.forecastWorkbenchDuplicateRows > 0 ? `<div class="forecast-data-check" role="status">库存快照发现 ${integer(state.forecastWorkbenchDuplicateRows)} 条重复 SKU 记录，本工作台已按 SKU 合并后计算，避免重复放大库存和补货建议。</div>` : ""}`;
      $$("#forecastWorkbenchFilters [data-forecast-filter]").forEach((button) =>
        button.classList.toggle("active", button.dataset.forecastFilter === state.forecastWorkbenchFilter));
      $("#forecastWorkbenchSearch").value = state.forecastWorkbenchSearch;
      $("#forecastWorkbenchSort").value = state.forecastWorkbenchSort;

      if (styles.length === 0) {
        body.innerHTML = emptyRow(11, "没有符合条件的款式", "请调整搜索或筛选条件后重试。");
        $("#forecastWorkbenchCount").textContent = `0 条结果，共 ${allStyles.length} 个款式`;
        return;
      }
      const expanded = state.forecastWorkbenchExpanded;
      body.innerHTML = styles.map((style) => {
        const open = expanded.has(style.parentSku);
        const detail = open ? renderWorkbenchDetail(style) : "";
        const riskMeta = inventoryRiskCopy[style.risk] || { label: style.risk || "待判断", group: "neutral" };
        const riskClass = style.riskRank <= 1 ? "critical" : style.riskRank <= 4 ? "warning" : "";
        const decisionMeta = forecastDecisionMeta(style.decision?.status);
        const trend = forecastTrendText(style.history);
        const image = style.imageUrl
          ? `<img class="forecast-product-image" src="${escapeHtml(style.imageUrl)}" alt="${escapeHtml(style.itemName || style.parentSku)}" loading="lazy" onerror="this.hidden=true;this.nextElementSibling.hidden=false"><span class="forecast-product-placeholder" aria-hidden="true" hidden>${escapeHtml(style.parentSku.replace(/^PNK/i, "").slice(0, 4) || "SKU")}</span>`
          : `<span class="forecast-product-placeholder" aria-hidden="true">${escapeHtml(style.parentSku.replace(/^PNK/i, "").slice(0, 4) || "SKU")}</span>`;
        const finalQuantity = style.decision?.finalQuantity;
        return `<tr class="forecast-style-row${open ? " open" : ""}" data-style="${escapeHtml(style.parentSku)}" role="button" tabindex="0" aria-expanded="${open}">
          <td class="po-toggle-col" data-label="展开"><span class="po-toggle">${open ? "▾" : "▸"}</span></td>
          <td data-label="款号与商品"><div class="forecast-product-cell">${image}<div><span class="forecast-parent-sku">${escapeHtml(style.parentSku)}</span><span class="forecast-product-meta"><span class="forecast-mini-badge ${riskClass}">${escapeHtml(riskMeta.label)}</span><span class="forecast-mini-badge">${integer(style.skuCount)} SKU</span>${style.oosCount ? `<span class="forecast-mini-badge critical">${integer(style.oosCount)} 断货</span>` : ""}</span></div></div></td>
          <td data-label="历史销量"><div class="forecast-history-cell"><div class="forecast-cell-stack"><strong>7天 ${integer(style.history.sales7)}</strong><span>30天 ${integer(style.history.sales30)}</span><span class="${trend.className}">${trend.text}</span></div>${forecastSparkline(style.history.daily14)}</div></td>
          <td class="num" data-label="当前库存"><div class="forecast-cell-stack"><strong>WFS ${integer(style.totalAvailable)}</strong><span>国内仓 ${integer(style.totalDomesticAvailable)}</span></div></td>
          <td class="num" data-label="总库存"><strong>${integer(style.totalSupply)}</strong></td>
          <td class="num" data-label="在途库存"><strong>${integer(style.totalTransit)}</strong></td>
          <td class="num" data-label="做货库存"><strong>${integer(style.totalProduction)}</strong></td>
          <td class="num" data-label="供应中"><div class="forecast-cell-stack"><strong>国内仓发 ${integer(style.totalDomesticShipment)}</strong><span>做货中 ${integer(style.totalWip)}</span></div></td>
          <td class="num" data-label="覆盖与风险"><div class="forecast-cell-stack"><strong class="${riskClass === "critical" ? "danger" : ""}" title="${escapeHtml(style.expectedStockoutVariant?.sku || "")}">P50 ${escapeHtml(forecastStockoutLabel(style.expectedStockoutDate, style.expectedStockoutVariant))}</strong><span title="${escapeHtml(style.highRiskStockoutVariant?.sku || "")}">P90 ${escapeHtml(forecastStockoutLabel(style.highRiskStockoutDate, style.highRiskStockoutVariant))}</span></div></td>
          <td class="num fc-figure" data-label="预测建议补货">${integer(style.totalFc)}</td>
          <td class="num" data-label="系统判断"><div class="forecast-cell-stack sys-judgment">
            <div class="sj-row"><span class="sj-label">工厂做货</span><strong class="sj-value prod-figure">${integer(style.totalProductionSuggested)}<span class="unit">件</span></strong></div>
            <div class="sj-row"><span class="sj-label">P90需求</span><span class="sj-value">${integer(style.totalP90Demand)}</span></div>
            <div class="sj-row"><span class="sj-label">模型</span><span class="sj-value sj-model">${escapeHtml(shortModelName([...style.forecastModels].join(" / ") || "待计算"))}</span></div>
            ${style.productionConfigIssues ? `<div class="sj-row sj-alert"><span class="sj-label">⚠ 裁床</span><span class="sj-value danger">${integer(style.productionConfigIssues)} 待维护</span></div>` : ""}
          </div></td>
          <td class="num" data-label="人工最终决策"><div class="forecast-cell-stack sys-judgment">
            <div class="sj-row"><span class="sj-label">最终数量</span><span class="sj-value forecast-decision-value ${finalQuantity == null ? "empty" : ""}">${finalQuantity == null ? "尚未填写" : integer(finalQuantity) + " 件"}</span></div>
            <div class="sj-row"><span class="sj-label">状态</span><span class="forecast-mini-badge ${decisionMeta.className}">${decisionMeta.label}</span></div>
            <div class="sj-row"><span class="sj-label">操作</span><button class="table-link forecast-open-decision" type="button" data-open-forecast-decision="${escapeHtml(style.parentSku)}">${style.decision ? "编辑决策" : "填写决策"}</button></div>
          </div></td>
        </tr>${detail}`;
      }).join("");
      $("#forecastWorkbenchCount").textContent = `显示 ${styles.length} 个款式，共 ${allStyles.length} 个款式`;

      $$("#forecastWorkbenchBody .forecast-style-row").forEach((tr) => {
        const parent = tr.dataset.style;
        const toggle = () => {
          if (expanded.has(parent)) expanded.delete(parent);
          else expanded.add(parent);
          renderDemandForecast();
        };
        tr.addEventListener("click", (e) => {
          if (e.target.closest("button, input, select, textarea, .factor-cell, a")) return;
          toggle();
        });
        tr.addEventListener("keydown", (e) => {
          if (e.target.closest("button, input, select, textarea, .factor-cell, a")) return;
          if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); }
        });
      });
    }

    function productionSuggestionStatusMeta(status) {
      return ({
        ALLOCATED: { label: "已按整床分配", className: "confirmed" },
        PLANNED: { label: "已计划做货", className: "confirmed" },
        PLANNED_LATE: { label: "已计划做货·到货晚", className: "confirmed" },
        PARTIALLY_COVERED: { label: "本床未完全覆盖", className: "warning" },
        BED_NOT_COVERED: { label: "本床未覆盖", className: "warning" },
        MANUAL_REVIEW: { label: "需人工复核", className: "warning" },
        CLEARANCE: { label: "清库存/停补", className: "" },
        RETIRED: { label: "淘汰款", className: "" },
        NEW_NO_HISTORY: { label: "新品待人工计划", className: "warning" },
        NOT_NEEDED: { label: "无需做货", className: "" },
        MISSING_CUTTING_YIELD: { label: "未维护裁床数", className: "warning" },
        INVALID_CUTTING_YIELD: { label: "裁床数须为非负整数", className: "warning" },
        MISSING_COLOR: { label: "缺少布色", className: "warning" }
      })[status] || { label: "待核对", className: "warning" };
    }

    function forecastColorProductionPlans(style) {
      const groups = new Map();
      style.items.forEach((item) => {
        const source = item.row || {};
        const color = String(source.color || "未标注颜色").trim() || "未标注颜色";
        if (!groups.has(color)) {
          groups.set(color, {
            color,
            raw: 0,
            production: 0,
            cuttingYield: null,
            status: "NOT_NEEDED",
            allocations: []
          });
        }
        const group = groups.get(color);
        group.raw += number(item.fc);
        group.production += number(item.productionFc);
        if (group.cuttingYield == null && number(source.cuttingYield) > 0) {
          group.cuttingYield = number(source.cuttingYield);
        }
        if (number(item.fc) > 0 || source.productionSuggestionStatus === "ALLOCATED") {
          group.status = source.productionSuggestionStatus || group.status;
        }
        if (number(item.productionFc) > 0) {
          group.allocations.push({
            size: source.size || item.sku,
            sku: item.sku,
            units: number(item.productionFc)
          });
        }
      });
      return [...groups.values()]
        .filter((group) => group.raw > 0 || group.production > 0)
        .map((group) => ({
          ...group,
          beds: group.cuttingYield > 0 && group.production > 0 ? group.production / group.cuttingYield : null
        }))
        .sort((a, b) => b.production - a.production || b.raw - a.raw || a.color.localeCompare(b.color));
    }

    function renderWorkbenchDetail(style) {
      const decision = style.decision || {};
      const status = decision.status || "DRAFT";
      const updatedAt = decision.updatedAt ? new Date(decision.updatedAt).toLocaleString("zh-CN") : "尚未保存";
      const colorPlans = forecastColorProductionPlans(style);
      const colorPlanRows = colorPlans.map((plan) => {
        const statusMeta = productionSuggestionStatusMeta(plan.status);
        const allocations = plan.allocations.length
          ? plan.allocations.map((item) => `${escapeHtml(item.size)} ${integer(item.units)}`).join(" · ")
          : "—";
        return `<tr>
          <td><strong>${escapeHtml(plan.color)}</strong></td>
          <td class="num">${integer(plan.raw)}</td>
          <td class="num">${plan.cuttingYield == null ? "—" : integer(plan.cuttingYield)}</td>
          <td class="num">${plan.beds == null ? "—" : number(plan.beds).toFixed(0)}</td>
          <td class="num"><strong>${integer(plan.production)}</strong></td>
          <td>${allocations}</td>
          <td><span class="forecast-mini-badge ${statusMeta.className}">${escapeHtml(statusMeta.label)}</span></td>
        </tr>`;
      }).join("") || `<tr><td colspan="7"><div class="empty-state"><strong>当前没有需要做货的布色</strong></div></td></tr>`;
      const skuPriority = (item) => {
        const plan = item.plan || {};
        if (plan.highRiskStockoutDate) return 0;
        if (plan.expectedStockoutDate) return 1;
        if (number(item.productionFc) > 0) return 2;
        if (number(plan.domesticShipmentUnits) > 0) return 3;
        return 4;
      };
      const rows = style.items.slice().sort((a, b) =>
        skuPriority(a) - skuPriority(b)
        || b.productionFc - a.productionFc
        || b.fc - a.fc
        || String(a.sku).localeCompare(String(b.sku))
      ).map((item) => {
        const source = item.row || {};
        const plan = item.plan || null;
        const planStatus = productionSuggestionStatusMeta(plan?.status || source.productionSuggestionStatus);
        const p90Demand = number(plan?.p90DemandUnits);
        const hasForecastDemand = p90Demand > 0;
        const riskClass = plan?.highRiskStockoutDate ? "critical" : plan?.expectedStockoutDate ? "warning" : "safe";
        const riskDate = (date) => date
          ? forecastDate(date)
          : hasForecastDemand ? "60天内无风险" : "无需求暂不判断";
        const needsManualReview = (plan?.status || source.productionSuggestionStatus) === "MANUAL_REVIEW";
        const actionClass = number(item.productionFc) > 0 ? "production" : number(plan?.domesticShipmentUnits) > 0 ? "ship" : "";
        const actionQuantity = number(item.productionFc);
        const actionHint = needsManualReview
          ? "新做货到货时点或整床余量需复核"
          : actionQuantity > 0
          ? `原始缺口 ${integer(item.fc)}`
          : number(plan?.domesticShipmentUnits) > 0
            ? `国内仓发 ${integer(plan.domesticShipmentUnits)}`
            : "当前无需补货";
        return `<tr class="forecast-sku-row">
          <td data-label="SKU"><div class="forecast-sku-identity"><span class="forecast-parent-sku" title="${escapeHtml(item.sku)}">${escapeHtml(item.sku)}</span></div></td>
          <td class="num" data-label="销量与需求"><div class="forecast-sku-demand"><strong>${plan ? integer(plan.p90DemandUnits) : "-"}</strong><span class="forecast-metric-label">P90 · 60天需求</span><span>近期 ${integer(source.recentUnits)} / ${integer(state.inventory?.meta?.sampleDays || 0)}天</span></div></td>
          <td class="num" data-label="可售库存"><div class="forecast-sku-stock"><strong>WFS ${integer(source.availableUnits)}</strong><span>国内仓 ${plan ? integer(plan.domesticAvailableUnits) : "-"}</span></div></td>
          <td class="num" data-label="供应中"><div class="forecast-sku-supply"><strong>在途 ${integer(source.transitUnits)}</strong><span class="supply-plan">国内仓发 ${plan ? integer(plan.domesticShipmentUnits) : "-"} · 30天到仓</span><span>做货中 ${plan ? integer(plan.wipUnits) : "-"}${plan?.wipAvailableDay ? ` · ${integer(plan.wipAvailableDay)}天可售` : ""}</span></div></td>
          <td class="num" data-label="断货预警"><div class="forecast-sku-risk ${riskClass}"><strong>P50 ${riskDate(plan?.expectedStockoutDate)}</strong><span>P90 ${riskDate(plan?.highRiskStockoutDate)}</span></div></td>
          <td class="num" data-label="60天执行建议"><div class="forecast-sku-action ${actionClass}"><span class="forecast-mini-badge ${planStatus.className}">${escapeHtml(planStatus.label)}</span><strong>${integer(actionQuantity)}<small>件</small></strong><span>${escapeHtml(actionHint)}</span></div></td>
        </tr>`;
      }).join("");
      return `<tr class="forecast-detail-row"><td colspan="11"><div class="forecast-detail-shell">
        <div class="forecast-decision-editor" data-forecast-decision-editor="${escapeHtml(style.parentSku)}">
          <div class="forecast-decision-field"><label for="decisionQty-${escapeHtml(style.parentSku)}">人工最终做货数量</label><input id="decisionQty-${escapeHtml(style.parentSku)}" data-decision-qty="${escapeHtml(style.parentSku)}" type="number" min="0" max="1000000" step="1" value="${decision.finalQuantity == null ? "" : escapeHtml(decision.finalQuantity)}" placeholder="填写整数"></div>
          <div class="forecast-decision-field"><label for="decisionStatus-${escapeHtml(style.parentSku)}">决策状态</label><select id="decisionStatus-${escapeHtml(style.parentSku)}" data-decision-status><option value="DRAFT"${status === "DRAFT" ? " selected" : ""}>待确认</option><option value="CONFIRMED"${status === "CONFIRMED" ? " selected" : ""}>已确认</option><option value="HOLD"${status === "HOLD" ? " selected" : ""}>暂缓</option></select></div>
          <div class="forecast-decision-field note"><label for="decisionNote-${escapeHtml(style.parentSku)}">决策备注</label><input id="decisionNote-${escapeHtml(style.parentSku)}" data-decision-note type="text" maxlength="500" value="${escapeHtml(decision.note || "")}" placeholder="例如：优先补核心尺码，等待货件到仓后再下单"></div>
          <div class="forecast-decision-actions"><button class="table-link" type="button" data-adopt-forecast-suggestion="${integer(style.totalProductionSuggested).replace(/,/g, "")}">采用裁床建议</button><button class="primary-button" type="button" data-save-forecast-decision>保存最终决策</button></div>
          <div class="forecast-decision-context"><span>原始缺口 <strong>${integer(style.totalFc)}</strong></span><span>裁床建议 <strong>${integer(style.totalProductionSuggested)}</strong></span><span>表单计划 <strong>${integer(style.totalPlan)}</strong></span><span>目标覆盖 <strong>${integer(state.inventoryTargetDays)} 天</strong></span><span>手动日均销量 <strong><span class="factor-cell" tabindex="0" data-parent="${escapeHtml(style.parentSku)}" data-field="manualDailySales" title="双击编辑按款号生效的日均销量">${factorCellText(style.manualDailySales)}</span></strong></span><span>上次保存 <strong>${escapeHtml(updatedAt)}</strong></span></div>
        </div>
        <section class="forecast-sku-section"><div class="forecast-sku-section-head"><h3>按布色裁床建议</h3><span>裁床数是一种布色一床的总做货量；完整床数向上取整，SKU 最低做货5件，之后按整数分配</span></div><div class="table-scroll forecast-sku-scroll"><table class="forecast-sku-table forecast-color-plan-table"><thead><tr><th>布色</th><th class="num">原始缺口</th><th class="num">裁床数/床</th><th class="num">建议床数</th><th class="num">建议做货</th><th>尺码分配</th><th>状态</th></tr></thead><tbody>${colorPlanRows}</tbody></table></div></section>
        <section class="forecast-sku-section"><div class="forecast-sku-section-head"><h3>SKU 60天补货执行清单</h3><span>优先显示高风险和需做货 SKU。国内仓发货 30 天到 WFS，工厂做货仅补国内仓与做货中库存抵减后的剩余缺口。</span></div><div class="table-scroll forecast-sku-scroll"><table class="forecast-sku-table forecast-replenishment-table"><thead><tr><th>SKU</th><th class="num">销量与需求<span>近期销量 / P90 60天</span></th><th class="num">可售库存<span>WFS / 国内仓</span></th><th class="num">供应中<span>在途 / 国内仓发 / 做货中</span></th><th class="num">断货预警<span>P50 / P90</span></th><th class="num">60天执行建议<span>建议做货量与状态</span></th></tr></thead><tbody>${rows}</tbody></table></div></section>
      </div></td></tr>`;
    }

    async function saveReplenishmentDecision(editor, button) {
      if (!editor || !button) return;
      const parentSku = editor.dataset.forecastDecisionEditor;
      const quantityInput = editor.querySelector("[data-decision-qty]");
      const statusInput = editor.querySelector("[data-decision-status]");
      const noteInput = editor.querySelector("[data-decision-note]");
      const rawQuantity = quantityInput.value.trim();
      const finalQuantity = rawQuantity === "" ? null : Number(rawQuantity);
      if (finalQuantity != null && (!Number.isFinite(finalQuantity) || finalQuantity < 0 || !Number.isInteger(finalQuantity))) {
        showToast("最终做货数量必须是大于或等于0的整数");
        quantityInput.focus();
        return;
      }
      if (statusInput.value === "CONFIRMED" && finalQuantity == null) {
        showToast("确认决策前请填写最终补货数量");
        quantityInput.focus();
        return;
      }
      const style = buildForecastWorkbench().find((item) => item.parentSku === parentSku);
      if (!style) return;
      button.disabled = true;
      button.textContent = "保存中";
      state.forecastSavingParents.add(parentSku);
      try {
        const response = await fetch(`/api/forecast/decisions/${encodeURIComponent(parentSku)}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            finalQuantity,
            systemSuggestedQuantity: style.totalProductionSuggested,
            demandPlanQuantity: style.totalPlan,
            targetDays: state.inventoryTargetDays,
            status: statusInput.value,
            note: noteInput.value.trim() || null
          })
        });
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        const saved = payload.data;
        const index = state.replenishmentDecisions.findIndex((item) => item.parentSku === parentSku);
        if (index >= 0) state.replenishmentDecisions[index] = saved;
        else state.replenishmentDecisions.push(saved);
        renderDemandForecast();
        showToast(`已保存 ${parentSku} 的最终补货决策`);
      } catch (error) {
        button.disabled = false;
        button.textContent = "保存最终决策";
        showToast(`决策保存失败：${error.message}`);
      } finally {
        state.forecastSavingParents.delete(parentSku);
      }
    }

    function activateFactorCell(cell) {
      if (cell.querySelector("input")) return;
      const sku = cell.dataset.sku;
      const parent = cell.dataset.parent;
      const field = cell.dataset.field;
      const current = cell.textContent.trim();
      const value = current === "—" ? "" : current;
      cell.innerHTML = "";
      const input = document.createElement("input");
      input.type = "number";
      input.step = (field === "returnRate" || field === "returnReuseRate") ? "0.01" : (field === "seasonalCoef" ? "0.1" : "1");
      input.value = value;
      input.className = "factor-edit-input";
      cell.appendChild(input);
      requestAnimationFrame(() => { input.focus(); input.select(); });
      const commit = async () => {
        input.removeEventListener("blur", commit);
        const raw = input.value.trim();
        const num = raw === "" ? null : Number(raw);
        if (num !== null && !Number.isFinite(num)) {
          showToast("请输入数字");
          cell.textContent = current;
          return;
        }
        if (num !== null && num < 0) {
          showToast("不能为负数");
          cell.textContent = current;
          return;
        }
        if ((field === "returnRate" || field === "returnReuseRate") && num !== null && (num < 0 || num > 1)) {
          showToast("退货率 / 重复利用率需在 0~1 之间");
          cell.textContent = current;
          return;
        }
        cell.innerHTML = factorCellText(num);
        try {
          if (parent) {
            const res = await fetch(`/api/forecast/parent-daily-sales/${encodeURIComponent(parent)}`, {
              method: "PATCH",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ field, value: num })
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const p = await res.json();
            state.parentDailySales = (p && p.data) || {};
          } else {
            const res = await fetch(`/api/forecast/factors/${encodeURIComponent(sku)}`, {
              method: "PATCH",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ field, value: num })
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const entry = (state.forecastFactors || []).find((f) => f.sku === sku);
            if (entry) entry[field] = num;
            else state.forecastFactors.push({ sku, [field]: num });
          }
          await fetchInventoryData();
          renderDemandForecast();
          showToast(parent ? "已按款号保存日均销量" : "因子已保存");
        } catch (error) {
          cell.textContent = current;
          showToast("保存失败：" + error.message);
        }
      };
      input.addEventListener("blur", commit);
      input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") { e.preventDefault(); input.blur(); }
        else if (e.key === "Escape") { e.preventDefault(); input.removeEventListener("blur", commit); cell.textContent = current; }
      });
    }

    let productionOrdersCache = null;
    let forecastDetailCache = [];
    async function openProductionOrderDrawer() {
      const drawer = $("#productionOrderDrawer");
      $("#productionOrderSummary").textContent = "正在读取…";
      $("#productionOrderBody").innerHTML = `<tr><td colspan="5" class="empty-state">正在读取生产订单…</td></tr>`;
      if (!drawer.open) drawer.showModal();
      try {
        const response = await fetch("/api/inventory/production-orders");
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        productionOrdersCache = payload.data || [];
        renderProductionOrders(state.productionOrderQuery || "");
      } catch (error) {
        $("#productionOrderBody").innerHTML = `<tr><td colspan="5" class="empty-state">读取失败：${escapeHtml(error.message)}</td></tr>`;
      }
    }

    function buildForecastByStyle() {
      const rows = (state.inventory && state.inventory.rows) || [];
      const byStyle = new Map();
      for (const r of rows) {
        const fc = number(r.forecastSuggestedUnits);
        if (fc <= 0) continue;
        const parent = r.parentSku || "（未分组）";
        if (!byStyle.has(parent)) byStyle.set(parent, { parentSku: parent, items: [], totalFc: 0 });
        const g = byStyle.get(parent);
        g.items.push({ sku: r.sku || "—", fc });
        g.totalFc += fc;
      }
      return [...byStyle.values()]
        .map((g) => ({ ...g, skuCount: g.items.length }))
        .sort((a, b) => compareStyleNo(a.parentSku, b.parentSku));
    }

    function openForecastDetailDrawer() {
      const drawer = $("#forecastDetailDrawer");
      forecastDetailCache = buildForecastByStyle();
      const totalUnits = forecastDetailCache.reduce((acc, s) => acc + s.totalFc, 0);
      $("#forecastDetailDrawerSummary").textContent = `共 ${forecastDetailCache.length} 个款式待补货 · 合计 ${integer(totalUnits)} 件`;
      renderForecastDetail();
      if (!drawer.open) drawer.showModal();
    }

    function renderForecastDetail() {
      const styles = forecastDetailCache;
      const expanded = state.forecastDetailExpanded;
      if (!styles.length) {
        $("#forecastDetailBody").innerHTML = `<tr><td colspan="4" class="empty-state">当前没有需要补货的款式</td></tr>`;
        return;
      }
      $("#forecastDetailBody").innerHTML = styles.map((s) => {
        const open = expanded.has(s.parentSku);
        const detail = open
          ? `<tr class="po-order-detail"><td colspan="4"><div class="po-detail-wrap"><table class="inventory-table po-detail-table">
              <thead><tr><th>SKU</th><th class="num">建议补货件数</th></tr></thead>
              <tbody>${s.items.slice().sort((a, b) => b.fc - a.fc).map((i) => `<tr>
                <td>${escapeHtml(i.sku)}</td>
                <td class="num">${integer(i.fc)}</td>
              </tr>`).join("")}</tbody>
            </table></div></td></tr>`
          : "";
        return `<tr class="po-order-row${open ? " open" : ""}" data-style="${escapeHtml(s.parentSku)}" role="button" tabindex="0">
          <td class="po-toggle-col"><span class="po-toggle">${open ? "▾" : "▸"}</span></td>
          <td>${escapeHtml(s.parentSku)}</td>
          <td class="num">${integer(s.skuCount)}</td>
          <td class="num">${integer(s.totalFc)}</td>
        </tr>${detail}`;
      }).join("");

      $$("#forecastDetailBody .po-order-row").forEach((tr) => {
        const parent = tr.dataset.style;
        const toggle = () => {
          if (expanded.has(parent)) expanded.delete(parent);
          else expanded.add(parent);
          renderForecastDetail();
        };
        tr.addEventListener("click", toggle);
        tr.addEventListener("keydown", (e) => {
          if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); }
        });
      });
    }

    function renderProductionOrders(query) {
      const all = productionOrdersCache || [];
      const q = (query || "").trim().toLowerCase();
      const rows = q
        ? all.filter((o) => (o.orderNo || "").toLowerCase().includes(q)
          || (o.styleNo || "").toLowerCase().includes(q)
          || (o.sku || "").toLowerCase().includes(q))
        : all;

      // 按订单编号聚合
      const byOrder = new Map();
      for (const o of rows) {
        const key = o.orderNo || "（无订单编号）";
        if (!byOrder.has(key)) byOrder.set(key, []);
        byOrder.get(key).push(o);
      }
      const orders = [...byOrder.entries()].map(([orderNo, items]) => {
        const total = items.reduce((acc, o) => acc + number(o.productionQty), 0);
        const shipDate = (items.find((i) => i.shipDate) || {}).shipDate || "";
        return { orderNo, items, total, skuCount: items.length, shipDate };
      }).sort((a, b) => b.total - a.total);

      const orderTotal = orders.reduce((acc, o) => acc + o.total, 0);
      $("#productionOrderSummary").textContent = `共 ${orders.length} 个订单 · 做货数量合计 ${integer(orderTotal)} 件`;

      if (!orders.length) {
        $("#productionOrderBody").innerHTML = `<tr><td colspan="5" class="empty-state">没有匹配的订单</td></tr>`;
        return;
      }

      const expanded = state.productionOrderExpanded;
      $("#productionOrderBody").innerHTML = orders.map((ord) => {
        const open = expanded.has(ord.orderNo);
        const detail = open
          ? `<tr class="po-order-detail"><td colspan="5"><div class="po-detail-wrap"><table class="inventory-table po-detail-table">
              <thead><tr><th>款号</th><th>SKU</th><th class="num">做货数量</th></tr></thead>
              <tbody>${ord.items.map((i) => `<tr>
                <td>${escapeHtml(i.styleNo || "")}</td>
                <td>${escapeHtml(i.sku || "")}</td>
                <td class="num">${integer(i.productionQty)}</td>
              </tr>`).join("")}</tbody>
            </table></div></td></tr>`
          : "";
        return `<tr class="po-order-row${open ? " open" : ""}" data-order="${escapeHtml(ord.orderNo)}" role="button" tabindex="0">
            <td class="po-toggle-col"><span class="po-toggle">${open ? "▾" : "▸"}</span></td>
            <td>${escapeHtml(ord.orderNo)}</td>
            <td class="num">${integer(ord.skuCount)}</td>
            <td class="num">${integer(ord.total)}</td>
            <td>${escapeHtml(ord.shipDate || "—")}</td>
          </tr>${detail}`;
      }).join("");

      $$("#productionOrderBody .po-order-row").forEach((tr) => {
        const orderNo = tr.dataset.order;
        const toggle = () => {
          if (expanded.has(orderNo)) expanded.delete(orderNo);
          else expanded.add(orderNo);
          renderProductionOrders(state.productionOrderQuery || "");
        };
        tr.addEventListener("click", toggle);
        tr.addEventListener("keydown", (e) => {
          if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); }
        });
      });
    }

    function localDateTimeValue(value = new Date()) {
      const local = new Date(value.getTime() - value.getTimezoneOffset() * 60000);
      return local.toISOString().slice(0, 16);
    }

    function renderOperationTargetOptions() {
      const type = $("#operationTargetType").value;
      $("#operationTargetOptions").innerHTML = state.operationTargets
        .filter((target) => target.targetType === type)
        .map((target) => `<option value="${escapeHtml(target.targetKey)}">${escapeHtml(target.targetName || target.parentSku || "")}</option>`)
        .join("");
    }

    async function loadOperationTargets() {
      try {
        const response = await fetch("/api/operations/targets?limit=200");
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        state.operationTargets = payload.data || [];
        renderOperationTargetOptions();
      } catch (error) {
        state.operationTargets = [];
        showToast(`SKU 目标读取失败：${error.message}`);
      }
    }

    function operationDelta(before, after) {
      if (after === null || after === undefined) return "等待后续数据";
      const left = number(before), right = number(after);
      if (!left) return right ? "由 0 新增" : "持平";
      const change = (right - left) / Math.abs(left);
      if (Math.abs(change) < .005) return "基本持平";
      return `${change > 0 ? "↑" : "↓"} ${Math.abs(change * 100).toFixed(1)}%`;
    }

    function operationImpactMetric(label, before, after, formatter) {
      const afterAvailable = after !== null && after !== undefined;
      return `<div class="operation-impact-metric"><span>${label}</span><strong>${afterAvailable ? `${formatter(before)} → ${formatter(after)}` : formatter(before)}</strong><small class="operation-delta">${operationDelta(before, after)}</small></div>`;
    }

    function renderOperations() {
      const payload = state.operations;
      if (!payload) return;
      const { meta, items } = payload;
      $("#operationRecordCount").textContent = integer(meta.recordCount);
      $("#operationLatestDate").textContent = meta.latestDataDate ? `数据截至 ${meta.latestDataDate}` : "暂无业务数据";
      $("#operationStatusHeadline").textContent = meta.recordCount ? "运营审计链已建立" : "还没有运营记录";
      $("#operationStatusDetail").textContent = meta.recordCount
        ? `${integer(meta.executedCount)} 条已执行，${integer(meta.readyCount)} 条已获得完整 ${meta.requestedDays} 天前后窗口。`
        : "先记录一次真实调整，系统会在后续数据导入后自动追踪。";
      $("#operationSummaryGrid").innerHTML = [
        ["当前记录", meta.recordCount, "含计划与已执行"],
        ["已执行", meta.executedCount, "进入效果追踪"],
        ["系统 / AI", meta.systemCount, "真实执行来源"],
        ["完整窗口", meta.readyCount, `${meta.requestedDays} 天前后齐全`],
      ].map(([label, value, note]) => `<div class="operation-summary"><span>${label}</span><strong>${integer(value)}</strong><span>${note}</span></div>`).join("");

      $("#operationList").innerHTML = items.map(({ record, impact }) => {
        const impactState = operationImpactCopy[impact.state] || operationImpactCopy.WAITING;
        const statusBadge = record.status === "EXECUTED" ? "scale" : record.status === "CANCELLED" ? "reduce" : "collect";
        const scope = record.targetType === "PARENT_SKU" ? "整款" : "变体 SKU";
        const beforeAfter = record.beforeValue || record.afterValue ? `<div class="operation-before-after">
          <div class="operation-value"><span>调整前</span><strong>${escapeHtml(record.beforeValue || "未填写")}</strong></div>
          <span class="operation-arrow">→</span>
          <div class="operation-value"><span>调整后</span><strong>${escapeHtml(record.afterValue || "未填写")}</strong></div>
        </div>` : "";
        let impactBody = `<div class="operation-impact-note">${escapeHtml(impact.message || "")}</div>`;
        if (impact.before) {
          const before = impact.before, after = impact.after;
          const range = after
            ? `${before.startDate}—${before.endDate} 对比 ${after.startDate}—${after.endDate}`
            : `操作前基线 ${before.startDate}—${before.endDate}`;
          impactBody = `<div class="operation-impact-head"><span>${range} · 排除操作当天</span><span class="status-badge ${impactState.badge}">${impactState.label}</span></div>
            <div class="operation-impact-grid">
              ${operationImpactMetric("销售额日均", before.salesPerDay, after?.salesPerDay, (value) => money(value, 2))}
              ${operationImpactMetric("订单件数日均", before.unitsPerDay, after?.unitsPerDay, (value) => `${number(value).toFixed(2)} 件`)}
              ${operationImpactMetric("广告费日均", before.adSpendPerDay, after?.adSpendPerDay, (value) => money(value, 2))}
              ${operationImpactMetric("广告 ROAS", before.roas, after?.roas, ratio)}
            </div>
            <div class="operation-impact-note">${escapeHtml(impact.message || "")}${impact.overlappingOperations ? `<br><span class="operation-overlap">同款比较窗口内另有 ${integer(impact.overlappingOperations)} 次已执行操作，结果可能受到共同影响。</span>` : ""}<br>这是数据相关性复盘，不直接证明单一操作造成了变化。</div>`;
        }
        return `<article class="operation-card">
          <div class="operation-card-header">
            <div class="operation-card-title">
              <div class="operation-card-meta">
                <span class="status-badge ${statusBadge}">${operationStatusCopy[record.status] || record.status}</span>
                <span class="status-badge collect">${operationSourceCopy[record.sourceType] || record.sourceType}</span>
                <span class="status-badge optimize">${scope}</span>
                <span class="status-badge collect">${operationActionCopy[record.actionType] || record.actionType}</span>
              </div>
              <h3>${escapeHtml(record.title)}</h3>
              <span class="operation-target">${escapeHtml(record.targetKey)}${record.targetName ? ` · ${escapeHtml(record.targetName)}` : ""}</span>
            </div>
            <time class="operation-time">${new Date(record.occurredAt).toLocaleString("zh-CN")}</time>
          </div>
          <div class="operation-copy">${beforeAfter}${record.note ? `<p>${escapeHtml(record.note)}</p>` : ""}</div>
          <div class="operation-impact">${impactBody}</div>
        </article>`;
      }).join("") || `<div class="empty-state"><strong>还没有运营记录</strong>在左侧记录一次备注、Listing、价格或广告调整。</div>`;
      updatePageMeta();
    }

    async function loadOperations() {
      if (state.operationsLoading) return;
      state.operationsLoading = true;
      $("#operationList").innerHTML = `<div class="empty-state"><strong>正在读取运营记录</strong>请稍候。</div>`;
      try {
        const query = state.operationSearch ? `&query=${encodeURIComponent(state.operationSearch)}` : "";
        const response = await fetch(`/api/operations?${analysisQuery({ limit: "50" })}${query}`);
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        state.operations = payload.data;
        renderOperations();
      } catch (error) {
        $("#operationStatusHeadline").textContent = "运营记录读取失败";
        $("#operationStatusDetail").textContent = error.message;
        $("#operationList").innerHTML = `<div class="empty-state"><strong>读取失败</strong>${escapeHtml(error.message)}</div>`;
      } finally {
        state.operationsLoading = false;
      }
    }

    function prepareOperation(targetType, targetKey) {
      $("#operationTargetType").value = targetType;
      renderOperationTargetOptions();
      $("#operationTargetKey").value = targetKey;
      $("#operationOccurredAt").value = localDateTimeValue();
      switchView("operations");
      $("#operationActionType").focus();
    }

    async function saveOperation(event) {
      event.preventDefault();
      const button = $("#saveOperationButton");
      button.disabled = true;
      button.textContent = "正在保存…";
      try {
        const response = await fetch("/api/operations", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            targetType: $("#operationTargetType").value,
            targetKey: $("#operationTargetKey").value.trim(),
            actionType: $("#operationActionType").value,
            sourceType: $("#operationSourceType").value,
            status: $("#operationStatus").value,
            occurredAt: $("#operationOccurredAt").value,
            title: $("#operationTitle").value.trim(),
            beforeValue: $("#operationBeforeValue").value.trim(),
            afterValue: $("#operationAfterValue").value.trim(),
            note: $("#operationNote").value.trim(),
          }),
        });
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        const targetType = $("#operationTargetType").value;
        const targetKey = $("#operationTargetKey").value;
        $("#operationForm").reset();
        $("#operationTargetType").value = targetType;
        $("#operationTargetKey").value = targetKey;
        $("#operationOccurredAt").value = localDateTimeValue();
        renderOperationTargetOptions();
        showToast(payload.message);
        await loadOperations();
      } catch (error) {
        showToast(`运营记录保存失败：${error.message}`);
      } finally {
        button.disabled = false;
        button.textContent = "保存运营记录";
      }
    }

    function updatePageMeta() {
      if (state.view === "sales" && state.sales?.meta) {
        const meta = state.sales.meta;
        $("#pageMeta").textContent = `${meta.periodDays} 天销量窗口 · ${meta.currentStart} 至 ${meta.currentEnd} · ${meta.sampleDays} 个销量日`;
        return;
      }
      if (state.view === "inventory" && state.inventory?.meta.snapshotAt) {
        const meta = state.inventory.meta;
        $("#pageMeta").textContent = `库存快照 ${new Date(meta.snapshotAt).toLocaleString("zh-CN")} · 销量范围 ${analysisPeriodLabel()} · ${meta.sampleDays} 个销量日`;
        return;
      }
      if (state.view === "products" && state.products?.meta) {
        const meta = state.products.meta;
        $("#pageMeta").textContent = `${meta.sourceFileCount} 个上传文件 · ${meta.parentProductCount} 个产品组 · ${meta.variantCount} 个 SKU 变体`;
        return;
      }
      if (state.view === "operations" && state.operations?.meta) {
        const meta = state.operations.meta;
        const operationRange = meta.startDate && meta.endDate ? ` · 记录范围 ${meta.startDate} 至 ${meta.endDate}` : "";
        $("#pageMeta").textContent = `效果窗口 ${meta.requestedDays} 天${operationRange} · ${meta.recordCount} 条记录 · 业务数据截至 ${meta.latestDataDate || "未知"}`;
        return;
      }
      $("#pageMeta").textContent = `数据截至 ${DATA.meta.latestDate} · ${analysisPeriodLabel()}`;
    }

    function renderAll() {
      renderMetrics();
      renderSkuSummary();
      renderDiagnosis();
      renderSkuMatrix();
      renderAgent();
      updatePageMeta();
    }

    // ===== 毛利估算计算器（纯前端，实时测算，不落库）=====
    function computeProfitEstimate(price, deliveryFee, purchaseCost, firstLeg, salesQty, adCost, refundRate, exchangeRate) {
      if (price == null || deliveryFee == null || purchaseCost == null || firstLeg == null || salesQty == null) return null;
      const b = Number(price), f = Number(deliveryFee), g = Number(purchaseCost), h = Number(firstLeg), c = Number(salesQty);
      const i = adCost == null || adCost === "" ? 1 : Number(adCost);
      const d = refundRate == null || refundRate === "" ? 0 : Number(refundRate);
      const q = exchangeRate == null || exchangeRate === "" ? 7 : Number(exchangeRate);
      const rate = b >= 20 ? 0.15 : (b < 15 ? 0.05 : 0.10);
      const j = b * rate;
      const k = b * 0.03;
      const unitNet = b - b * d - f - j - k;
      const raw = unitNet * q * c - i * q - (g + h) * c + c * d * 0.7 * (g + h);
      const profit = Math.round(raw * 100) / 100;
      const revenue = b * c * q;
      const margin = revenue === 0 ? 0 : profit / revenue;
      return { profit, margin, commission: j, storage: k };
    }

    const PE_FIELDS = ["pePrice", "peDeliveryFee", "pePurchaseCost", "peFirstLeg", "peSalesQty", "peAdCost", "peRefundRate", "peExchangeRate"];

    function updateProfitCalculator() {
      const [price, deliveryFee, purchaseCost, firstLeg, salesQty, adCost, refundRate, exchangeRate] = PE_FIELDS.map((id) => document.getElementById(id).value);
      const r = computeProfitEstimate(price, deliveryFee, purchaseCost, firstLeg, salesQty, adCost, refundRate, exchangeRate);
      document.getElementById("peCommission").textContent = r ? `¥${r.commission.toFixed(2)}` : "—";
      document.getElementById("peStorage").textContent = r ? `¥${r.storage.toFixed(2)}` : "—";
      document.getElementById("peProfit").textContent = r ? `¥${r.profit.toFixed(2)}` : "—";
      document.getElementById("peMargin").textContent = r ? `${(r.margin * 100).toFixed(2)}%` : "—";
    }

    function bindProfitCalculator() {
      PE_FIELDS.forEach((id) => {
        const el = document.getElementById(id);
        el.addEventListener("input", updateProfitCalculator);
        el.addEventListener("change", updateProfitCalculator);
      });
      const resetBtn = document.getElementById("resetProfitCalculator");
      if (resetBtn) resetBtn.addEventListener("click", () => {
        ["pePrice", "peSalesQty", "peRefundRate", "peDeliveryFee", "pePurchaseCost", "peFirstLeg", "peAdCost"].forEach((id) => { document.getElementById(id).value = ""; });
        document.getElementById("peExchangeRate").value = "7";
        updateProfitCalculator();
      });
      // 工具分组折叠（与计算器解耦）
      $$('[data-nav-group]').forEach((toggle) => {
        toggle.addEventListener("click", () => {
          const expanded = toggle.getAttribute("aria-expanded") === "true";
          toggle.setAttribute("aria-expanded", String(!expanded));
          const sublist = document.querySelector(`[data-nav-sublist="${toggle.dataset.navGroup}"]`);
          if (sublist) sublist.hidden = expanded;
        });
      });
    }

    /* ===== 流水线装箱转换工具（移植自 box_gavin.html，已去除维格登记外部调用） ===== */
    function initBoxConvert() {
      const XLSX = window.XLSX;
      if (!XLSX) { console.error("SheetJS 未加载"); return; }
      const $bc = (id) => document.getElementById(id);
      const drop = $bc("bcDrop");
      const fileInput = $bc("bcFile");
      const fileNameEl = $bc("bcFileName");
      const sheetPanel = $bc("bcSheetPanel");
      const sheetList = $bc("bcSheetList");
      const resultPanel = $bc("bcResultPanel");
      const convertBtn = $bc("bcConvertBtn");
      const downloadBtn = $bc("bcDownloadBtn");
      const summaryEl = $bc("bcSummary");

      let workbook = null;
      let selectedFileName = "";
      let outWorkbook = null;
      let copyBox = null, copyEu = null, copyUs = null;

      function doFile(files) {
        const f = files && files[0];
        if (!f) return;
        selectedFileName = f.name;
        fileNameEl.textContent = "已选择文件：" + f.name;
        const reader = new FileReader();
        reader.onload = (e) => {
          try {
            workbook = XLSX.read(new Uint8Array(e.target.result), { type: "array" });
          } catch (err) {
            alert("文件读取失败，请确认是有效的 Excel 文件：" + err.message);
            return;
          }
          processWorkbook();
        };
        reader.readAsArrayBuffer(f);
      }

      function processWorkbook() {
        sheetList.innerHTML = "";
        workbook.SheetNames.forEach((name, idx) => {
          const label = document.createElement("label");
          label.className = "bc-sheet-option";
          const radio = document.createElement("input");
          radio.type = "radio";
          radio.name = "bcSheets";
          radio.value = name;
          if (idx === 0) radio.checked = true;
          label.appendChild(radio);
          label.appendChild(document.createTextNode(" " + name));
          sheetList.appendChild(label);
        });
        sheetPanel.hidden = false;
        resultPanel.hidden = true;
        downloadBtn.hidden = true;
        summaryEl.innerHTML = "";
      }

      function createCellPos(n) {
        const ordA = "A".charCodeAt(0);
        const ordZ = "Z".charCodeAt(0);
        const len = ordZ - ordA + 1;
        let s = "";
        let x = n;
        while (x >= 0) {
          s = String.fromCharCode(x % len + ordA) + s;
          x = Math.floor(x / len) - 1;
        }
        return s;
      }

      function convert() {
        if (!workbook) { alert("请先上传装箱 Excel 文件"); return; }
        let sheetName = null;
        document.getElementsByName("bcSheets").forEach((r) => { if (r.checked) sheetName = r.value; });
        if (!sheetName) { alert("请选择工作表"); return; }

        const ws = workbook.Sheets[sheetName];
        const data = XLSX.utils.sheet_to_json(ws, { header: 1 });
        const header = data[0];

        const boxStrCol = header.findIndex((it) => it && String(it).startsWith("箱号") && String(it).includes("扫码"));
        const skuCol = header.findIndex((it) => it && String(it).startsWith("SKU") && String(it).includes("抓取"));
        if (boxStrCol < 0 || skuCol < 0) {
          alert("未找到「箱号（扫码）」或「SKU（抓取）」列，请确认工作表正确。");
          return;
        }

        let boxNoArr = [["箱号（扫码）", "箱子序号（上传亚马逊的最终序号）", "品名", "重量(KG)", "高度(cm)", "每箱数量"]];
        let boxNoArrNo = 1;

        for (let i = 1; i < data.length; i++) {
          const row = data[i];
          if (row[boxStrCol] && String(row[boxStrCol]).trim()) {
            if (boxNoArr.findIndex((it) => it[0] == row[boxStrCol]) !== -1) {
              alert("箱号重复，请修正后重试！报错箱号：" + row[boxStrCol]); return;
            }
            if (!row[skuCol]) { alert("箱号所在行缺少SKU；请输入完整信息后重试！报错箱号：" + row[boxStrCol]); return; }
            if (!row[skuCol + 3]) { alert("箱号所在行缺少重量信息；请输入完整信息后重试！报错箱号：" + row[boxStrCol]); return; }
            if (!row[skuCol + 4]) { alert("箱号所在行缺少箱子规格信息；请输入完整信息后重试！报错箱号：" + row[boxStrCol]); return; }
            boxNoArr.push([row[boxStrCol], "B" + boxNoArrNo, "", row[skuCol + 3], row[skuCol + 4]]);
            boxNoArrNo++;
          }
        }

        const boxNum = boxNoArr.length - 1;
        if (boxNum === 0) { alert("未识别到任何装箱数据行（箱号列需有值）。"); return; }

        let resultsSheet = [["SKU", "FNSKU", "数量(计划上加减条码后的数量)", "验证", "右侧求和"]];
        let thirdSheet = [["父SKU"]];
        let sumSheet = [["箱数", boxNoArrNo - 1], ["重量"], ["每箱向上取整(1kg)重量和"], ["发货数量"], ["品名数量"]];

        for (let i = 0; i < boxNum; i++) {
          resultsSheet[0].push("箱" + (i + 1));
          thirdSheet[0].push("箱" + (i + 1));
        }

        let skuLists = [], pSkuLists = [];
        for (let i = 1; i < data.length; i++) {
          const row = data[i];
          if (row[skuCol]) {
            if (skuLists.indexOf(row[skuCol]) === -1) {
              skuLists.push(row[skuCol]);
              const pSku = row[skuCol].slice(0, row[skuCol].indexOf("-") + 1);
              if (pSkuLists.indexOf(pSku) === -1) pSkuLists.push(pSku);
            }
          }
        }
        skuLists.sort();
        pSkuLists.sort();

        let spuNum = [];
        for (let n = 0; n < pSkuLists.length; n++) {
          thirdSheet.push([pSkuLists[n]]);
          spuNum.push([pSkuLists[n]]);
        }

        const height0 = data[1] ? data[1][skuCol + 4] : 50;
        const boxWidthNum = height0 == 60 ? 35 : 40;
        const boxLenNum = height0 == 60 ? 40 : 50;
        const firstW = data[1] ? Number(data[1][skuCol + 3] || 0) : 0;
        const boxWeight = ["", "", "", "", "重量(KG)：", data[1] ? data[1][skuCol + 3] : ""];
        const boxWidth = ["", "", "", "", "宽度(cm)：", boxWidthNum];
        const boxLen = ["", "", "", "", "长度(cm)：", boxLenNum];
        const boxHeigh = ["", "", "", "", "高度(cm)：", height0];
        const boxWeightLb = ["", "", "美国：", "", "重量(磅)：", parseFloat((firstW * 2.2046226).toFixed(2))];
        const boxWidthIn = ["", "", "", "", "宽度(in)：", parseFloat((boxWidthNum * 0.393700787).toFixed(2))];
        const boxLenIn = ["", "", "", "", "长度(in)：", parseFloat((boxLenNum * 0.393700787).toFixed(2))];
        const boxHeighIn = ["", "", "", "", "高度(in)：", parseFloat((height0 * 0.393700787).toFixed(2))];

        let sumWeight = firstW;
        let sumNum = 0;
        let boxNo = 1;

        for (let j = 1; j < data.length; j++) {
          const row = data[j];
          if (row.length >= skuCol + 1 && row[skuCol] && row[skuCol] != "#N/A") {
            sumNum += (row[skuCol + 2] || 0);
            if (j > 1 && row[boxStrCol] && String(row[boxStrCol]).trim()) {
              const h = row[skuCol + 4];
              const w = h == 60 ? 35 : 40;
              const l = h == 60 ? 40 : 50;
              const w3 = Number(row[skuCol + 3] || 0);
              boxWeight.push(w3);
              sumWeight += w3;
              boxWidth.push(w);
              boxLen.push(l);
              boxHeigh.push(h);
              boxWeightLb.push(parseFloat((w3 * 2.2046226).toFixed(2)));
              boxWidthIn.push(parseFloat((w * 0.393700787).toFixed(2)));
              boxLenIn.push(parseFloat((l * 0.393700787).toFixed(2)));
              boxHeighIn.push(parseFloat((h * 0.393700787).toFixed(2)));
              boxNo += 1;
            }
            const resultsIdx = skuLists.indexOf(row[skuCol]);
            const pIdx = pSkuLists.findIndex((p) => row[skuCol].startsWith(p));
            if (pIdx >= 0) {
              if (!thirdSheet[pIdx + 1][boxNo]) thirdSheet[pIdx + 1][boxNo] = 0;
              thirdSheet[pIdx + 1][boxNo] += (row[skuCol + 2] || 0);
              if (spuNum[pIdx].length < 2) spuNum[pIdx].push(0);
              spuNum[pIdx][1] += (row[skuCol + 2] || 0);
            }
            if (resultsSheet[resultsIdx + 1] && resultsSheet[resultsIdx + 1][0]) {
              if (resultsSheet[resultsIdx + 1][boxNo + 4]) {
                resultsSheet[resultsIdx + 1][boxNo + 4] += (row[skuCol + 2] || 0);
              } else {
                resultsSheet[resultsIdx + 1][boxNo + 4] = (row[skuCol + 2] || 0);
              }
            } else {
              resultsSheet[resultsIdx + 1] = [row[skuCol], row[skuCol + 1]];
              resultsSheet[resultsIdx + 1][boxNo + 4] = (row[skuCol + 2] || 0);
            }
          }
        }

        const spuNumStr = spuNum.map((a) => a.join("")).join("\n");
        const sumWeightCeil = boxWeight.slice(5).map((w) => Number(w || 0)).reduce((acc, w) => acc + Math.ceil(w), 0);
        sumSheet[1].push(sumWeight);
        sumSheet[2].push(sumWeightCeil);
        sumSheet[3].push(sumNum);
        sumSheet[4].push(spuNumStr);

        resultsSheet.push([]); resultsSheet.push([]);
        resultsSheet.push(["", "", "", "", "每箱数量："]); resultsSheet.push([]);
        resultsSheet.push(["", "", "", "", "平均毛重："]); resultsSheet.push([]);
        resultsSheet.push(["箱子重量按1kg计算", "", "", "", "平均净重："]); resultsSheet.push([]);
        resultsSheet.push(boxWeight); resultsSheet.push(boxWidth); resultsSheet.push(boxLen); resultsSheet.push(boxHeigh);
        resultsSheet.push([[]]); resultsSheet.push([[]]);
        resultsSheet.push(boxWeightLb); resultsSheet.push(boxWidthIn); resultsSheet.push(boxLenIn); resultsSheet.push(boxHeighIn);
        resultsSheet.push([[]]); resultsSheet.push(boxWeight); resultsSheet.push(boxLen); resultsSheet.push(boxWidth); resultsSheet.push(boxHeigh);

        const wsResult = XLSX.utils.aoa_to_sheet(resultsSheet);
        const wsBox = XLSX.utils.aoa_to_sheet(boxNoArr);
        const wsThird = XLSX.utils.aoa_to_sheet(thirdSheet);
        const wsSum = XLSX.utils.aoa_to_sheet(sumSheet);

        for (let i = 0; i < skuLists.length; i++) {
          wsResult["D" + (i + 2)] = { f: "C" + (i + 2) + "=E" + (i + 2) };
          wsResult["E" + (i + 2)] = { f: "SUM(F" + (i + 2) + ":ZZ" + (i + 2) + ")" };
        }
        for (let i = 0; i < boxNoArrNo; i++) {
          wsResult[createCellPos(i + 5) + (skuLists.length + 4)] = { f: "SUM(" + createCellPos(i + 5) + "2:" + createCellPos(i + 5) + (skuLists.length + 2) + ")" };
          wsResult[createCellPos(i + 5) + (skuLists.length + 6)] = { f: "ROUND(" + createCellPos(i + 5) + (skuLists.length + 10) + "/" + createCellPos(i + 5) + (skuLists.length + 4) + ",3)" };
          wsResult[createCellPos(i + 5) + (skuLists.length + 8)] = { f: "ROUND((" + createCellPos(i + 5) + (skuLists.length + 10) + "-1)/" + createCellPos(i + 5) + (skuLists.length + 4) + ",3)" };
          if (i > 0) wsBox["F" + (i + 1)] = { f: "SUM(按父SKU汇总数量!" + createCellPos(i) + ":" + createCellPos(i) + ")" };
        }

        wsResult["!cols"] = [{ wch: 45 }, { wch: 11 }, { wch: 6 }, { wch: 9 }, { wch: 9 }];
        wsThird["!cols"] = [{ wch: 20 }];
        wsBox["!cols"] = [{ wch: 25 }, { wch: 30 }];
        wsResult["!freeze"] = { xSplit: 1, ySplit: 1, state: "frozen" };
        wsBox["!freeze"] = { ySplit: 1, state: "frozen" };
        wsThird["!freeze"] = { xSplit: 1, ySplit: 1, state: "frozen" };

        outWorkbook = XLSX.utils.book_new();
        let xlsName = "转换结果-" + selectedFileName.replace(/\.[^.]+$/, "");
        if (xlsName.length > 28) xlsName = xlsName.substr(0, 14) + ".." + xlsName.substr(-14, 14);
        XLSX.utils.book_append_sheet(outWorkbook, wsResult, "转换结果");
        XLSX.utils.book_append_sheet(outWorkbook, wsBox, "箱子编号对应关系");
        XLSX.utils.book_append_sheet(outWorkbook, wsThird, "按父SKU汇总数量");
        XLSX.utils.book_append_sheet(outWorkbook, wsSum, "汇总");

        copyBox = resultsSheet.slice(1, 1 + skuLists.length).map((r) => (r || []).slice(5));
        copyEu = [boxWeight, boxWidth, boxLen, boxHeigh].map((r) => (r || []).slice(5));
        copyUs = [boxWeightLb, boxWidthIn, boxLenIn, boxHeighIn].map((r) => (r || []).slice(5));

        summaryEl.innerHTML = `<table class="bc-summary-table"><tbody>
          <tr><th>箱数</th><td>${boxNoArrNo - 1}</td></tr>
          <tr><th>重量</th><td>${sumWeight}</td></tr>
          <tr><th>每箱向上取整(1kg)重量和</th><td>${sumWeightCeil} KG</td></tr>
          <tr><th>发货数量</th><td>${sumNum}</td></tr>
          <tr><th>品名数量</th><td style="white-space:pre-wrap">${spuNumStr}</td></tr>
        </tbody></table>`;
        resultPanel.hidden = false;
        downloadBtn.hidden = false;
        showToast("转换完成，可下载结果或复制信息。");
      }

      function download() {
        if (!outWorkbook) return;
        let xlsName = "转换结果-" + selectedFileName.replace(/\.[^.]+$/, "");
        if (xlsName.length > 28) xlsName = xlsName.substr(0, 14) + ".." + xlsName.substr(-14, 14);
        XLSX.writeFile(outWorkbook, xlsName + ".xlsx");
      }

      function fallbackCopy(text) {
        const ta = document.createElement("textarea");
        ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0";
        document.body.appendChild(ta); ta.select();
        try { document.execCommand("copy"); showToast("复制成功！"); }
        catch (e) { showToast("复制失败，请手动选择。"); }
        document.body.removeChild(ta);
      }

      function copy(kind) {
        const arr = kind === "box" ? copyBox : kind === "eu" ? copyEu : kind === "us" ? copyUs : null;
        if (!arr) return;
        const text = arr.map((r) => (r || []).join("\t")).join("\n");
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(() => showToast("复制成功！")).catch(() => fallbackCopy(text));
        } else {
          fallbackCopy(text);
        }
      }

      if (drop) {
        ["dragenter", "dragover"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); e.stopPropagation(); drop.classList.add("bc-drop-over"); }));
        ["dragleave", "drop"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); e.stopPropagation(); drop.classList.remove("bc-drop-over"); }));
        drop.addEventListener("drop", (e) => { if (e.dataTransfer && e.dataTransfer.files) doFile(e.dataTransfer.files); });
      }
      if (fileInput) fileInput.addEventListener("change", (e) => doFile(e.target.files));
      if (convertBtn) convertBtn.addEventListener("click", convert);
      if (downloadBtn) downloadBtn.addEventListener("click", download);
      document.querySelectorAll("#view-box-convert .bc-copy").forEach((btn) => btn.addEventListener("click", () => copy(btn.dataset.copy)));
    }

    function switchView(view) {
      state.view = view;
      history.replaceState(null, "", `#${view}`);
      $$(".nav-button").forEach((button) => button.classList.toggle("active", button.dataset.view === view));
      $$(".view").forEach((section) => section.classList.toggle("active", section.id === `view-${view}`));
      $("#pageTitle").textContent = titles[view];
      updatePageMeta();
      document.body.classList.remove("sidebar-open");
      $("#menuButton").setAttribute("aria-expanded", "false");
      window.scrollTo({ top: 0, behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
      if (view === "profit-estimate") {
        const tg = document.querySelector('[data-nav-group="tools"]');
        const sl = document.querySelector('[data-nav-sublist="tools"]');
        if (tg) tg.setAttribute("aria-expanded", "true");
        if (sl) sl.hidden = false;
        updateProfitCalculator();
      }
      if (view === "box-convert") {
        const tg = document.querySelector('[data-nav-group="tools"]');
        const sl = document.querySelector('[data-nav-sublist="tools"]');
        if (tg) tg.setAttribute("aria-expanded", "true");
        if (sl) sl.hidden = false;
      }
      if (view === "inventory" || view === "demand-forecast") {
        const tg = document.querySelector('[data-nav-group="inventory"]');
        const sl = document.querySelector('[data-nav-sublist="inventory"]');
        if (tg) tg.setAttribute("aria-expanded", "true");
        if (sl) sl.hidden = false;
      }
      if (view === "inventory") {
        scheduleInventoryTrendRender();
      }
      if (view === "demand-forecast") {
        loadDemandForecast();
      }
      if (view === "recent") {
        loadRecent();
      }
      if (view === "dashboard" && DATA.daily?.length) {
        requestAnimationFrame(() => renderTrend());
      }
    }

    function emptyRow(columns, title, detail) { return `<tr><td colspan="${columns}"><div class="empty-state"><strong>${title}</strong>${detail}</div></td></tr>`; }
    function escapeHtml(value) { return String(value ?? "").replace(/[&<>"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" })[char]); }
    function showToast(message) { const toast = $("#toast"); toast.textContent = message; toast.classList.add("show"); clearTimeout(showToast.timer); showToast.timer = setTimeout(() => toast.classList.remove("show"), 3400); }

    function syncPeriodControls() {
      $$("[data-period]").forEach((button) => {
        button.classList.toggle(
          "active",
          !state.customPeriodActive && number(button.dataset.period) === state.periodDays
        );
      });
      const trigger = $("#customPeriodTrigger");
      const value = $("#customPeriodButtonValue");
      trigger.classList.toggle("active", state.customPeriodActive);
      trigger.setAttribute(
        "aria-label",
        state.customPeriodActive ? `自定义日期范围，${analysisPeriodLabel()}` : "设置自定义日期范围"
      );
      value.hidden = !state.customPeriodActive;
      value.textContent = state.customPeriodActive
        ? `${shortDate(state.customPeriodStartDate)}–${shortDate(state.customPeriodEndDate)}`
        : "";
    }

    function closeCustomPeriod(focusTrigger = false) {
      const trigger = $("#customPeriodTrigger");
      const popover = $("#customPeriodPopover");
      if (!trigger || !popover) return;
      popover.hidden = true;
      trigger.setAttribute("aria-expanded", "false");
      if (focusTrigger) trigger.focus();
    }

    function validateCustomPeriod(showError = true) {
      const startInput = $("#customPeriodStart");
      const endInput = $("#customPeriodEnd");
      const error = $("#customPeriodError");
      const startDate = startInput.value;
      const endDate = endInput.value;
      const minDate = DATA.meta.firstDate || startInput.min;
      const maxDate = DATA.meta.latestDate;
      const valid = Boolean(startDate && endDate)
        && startDate <= endDate
        && (!minDate || startDate >= minDate)
        && endDate <= maxDate;
      startInput.setAttribute("aria-invalid", String(!valid));
      endInput.setAttribute("aria-invalid", String(!valid));
      error.hidden = valid || !showError;
      return valid ? { startDate, endDate } : null;
    }

    function applyPeriod(days) {
      state.periodDays = Math.max(1, Math.min(365, Math.round(number(days))));
      state.customPeriodActive = false;
      state.customPeriodStartDate = null;
      state.customPeriodEndDate = null;
      syncPeriodControls();
      renderAll();
      loadSales();
      loadInventory();
      loadOperations();
    }

    function applyCustomPeriod(startDate, endDate) {
      state.periodDays = inclusiveDays(startDate, endDate);
      state.customPeriodActive = true;
      state.customPeriodStartDate = startDate;
      state.customPeriodEndDate = endDate;
      syncPeriodControls();
      renderAll();
      loadSales();
      loadInventory();
      loadOperations();
    }

    function initializeCustomPeriodControl() {
      const control = $("[data-custom-period-control]");
      const trigger = $("#customPeriodTrigger");
      const popover = $("#customPeriodPopover");
      const startInput = $("#customPeriodStart");
      const endInput = $("#customPeriodEnd");
      if (!control || !trigger || !popover || !startInput || !endInput) return;
      const minDate = DATA.meta.firstDate || DATA.meta.latestDate;
      startInput.min = endInput.min = minDate;
      startInput.max = endInput.max = DATA.meta.latestDate;
      trigger.addEventListener("click", () => {
        const willOpen = popover.hidden;
        if (!willOpen) {
          closeCustomPeriod();
          return;
        }
        closeImportScopes();
        const range = activePeriodRange();
        startInput.value = range.startDate < minDate ? minDate : range.startDate;
        endInput.value = range.endDate;
        validateCustomPeriod(false);
        popover.hidden = false;
        trigger.setAttribute("aria-expanded", "true");
        requestAnimationFrame(() => {
          startInput.focus();
        });
      });
      $("#cancelCustomPeriod").addEventListener("click", () => closeCustomPeriod(true));
      popover.addEventListener("submit", (event) => {
        event.preventDefault();
        const range = validateCustomPeriod(true);
        if (range == null) {
          startInput.focus();
          return;
        }
        applyCustomPeriod(range.startDate, range.endDate);
        closeCustomPeriod(true);
        showToast(`分析范围已切换为 ${range.startDate} 至 ${range.endDate}。`);
      });
      [startInput, endInput].forEach((input) => {
        input.addEventListener("input", () => validateCustomPeriod(false));
        input.addEventListener("blur", () => validateCustomPeriod(Boolean(startInput.value && endInput.value)));
      });
      document.addEventListener("click", (event) => {
        if (!event.target.closest("[data-custom-period-control]")) closeCustomPeriod();
      });
      document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && !popover.hidden) closeCustomPeriod(true);
      });
      syncPeriodControls();
    }

    function closeImportScopes(except = null) {
      $$("[data-import-control]").forEach((control) => {
        if (control === except) return;
        const trigger = control.querySelector(".import-scope-trigger");
        const popover = control.querySelector(".import-scope-popover");
        trigger.setAttribute("aria-expanded", "false");
        popover.hidden = true;
      });
    }

    function syncImportControls() {
      const selectedCount = state.importSources.size;
      $$("[data-import-source]").forEach((checkbox) => {
        checkbox.checked = state.importSources.has(checkbox.value);
      });
      $$("[data-import-count]").forEach((counter) => {
        counter.textContent = `${selectedCount}/${importSources.length}`;
      });
      [$("#scanImportButton"), $("#scanImportPageButton")].filter(Boolean).forEach((button) => {
        button.disabled = state.importRunning || selectedCount === 0;
      });
    }

    function initializeImportControls() {
      const optionMarkup = importSourceGroups.map((group) => `
        <fieldset class="import-source-group">
          <legend>${escapeHtml(group.label)}</legend>
          ${group.sources.map((source) => `
            <label class="import-source-option">
              <input type="checkbox" value="${escapeHtml(source.key)}" data-import-source checked>
              <span><strong>${escapeHtml(source.label)}</strong><small>${escapeHtml(source.hint)}</small></span>
            </label>
          `).join("")}
        </fieldset>
      `).join("");

      $$("[data-import-control]").forEach((control) => {
        control.querySelector("[data-import-options]").innerHTML = optionMarkup;
        const trigger = control.querySelector(".import-scope-trigger");
        const popover = control.querySelector(".import-scope-popover");
        trigger.addEventListener("click", () => {
          const willOpen = popover.hidden;
          closeImportScopes(willOpen ? control : null);
          popover.hidden = !willOpen;
          trigger.setAttribute("aria-expanded", String(willOpen));
        });
        control.addEventListener("change", (event) => {
          const checkbox = event.target.closest("[data-import-source]");
          if (!checkbox) return;
          if (checkbox.checked) state.importSources.add(checkbox.value);
          else state.importSources.delete(checkbox.value);
          syncImportControls();
        });
        control.addEventListener("click", (event) => {
          const action = event.target.closest("[data-import-action]")?.dataset.importAction;
          if (!action) return;
          if (action === "all") state.importSources = new Set(importSources.map((source) => source.key));
          if (action === "clear") state.importSources.clear();
          syncImportControls();
        });
      });

      document.addEventListener("click", (event) => {
        if (!event.target.closest("[data-import-control]")) closeImportScopes();
      });
      document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") closeImportScopes();
      });
      syncImportControls();
    }

    async function runImport() {
      const selectedSources = importSources
        .map((source) => source.key)
        .filter((key) => state.importSources.has(key));
      if (!selectedSources.length) {
        showToast("请至少选择一项导入内容。");
        return;
      }
      closeImportScopes();
      const buttons = [$("#scanImportButton"), $("#scanImportPageButton")].filter(Boolean);
      const query = new URLSearchParams();
      selectedSources.forEach((source) => query.append("source", source));
      state.importRunning = true;
      buttons.forEach((button) => { button.disabled = true; button.textContent = "正在扫描…"; });
      try {
        const response = await fetch(`/api/imports/scan?${query}`, { method: "POST" });
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        showToast(payload.data.message);
        await loadBatches();
        setTimeout(() => location.reload(), 900);
      } catch (error) {
        showToast(`导入失败：${error.message}`);
      } finally {
        state.importRunning = false;
        buttons.forEach((button) => { button.textContent = button.id === "scanImportButton" ? "扫描导入" : "开始扫描导入"; });
        syncImportControls();
      }
    }

    async function loadBatches() {
      const body = $("#batchBody");
      if (!body) return;
      body.innerHTML = emptyRow(8, "正在读取导入记录", "请稍候。");
      try {
        const response = await fetch("/api/imports/batches?limit=30");
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        body.innerHTML = payload.data.map((batch) => `<tr><td class="mono">#${batch.id}</td><td>${new Date(batch.startedAt).toLocaleString("zh-CN")}</td><td><span class="status-badge ${batch.status === "SUCCESS" ? "scale" : batch.status === "FAILED" ? "reduce" : "optimize"}">${escapeHtml(batch.status)}</span></td><td class="num">${integer(batch.discovered)}</td><td class="num">${integer(batch.imported)}</td><td class="num">${integer(batch.skipped)}</td><td class="num">${integer(batch.failed)}</td><td>${escapeHtml(batch.message || "")}</td></tr>`).join("") || emptyRow(8, "还没有导入记录", "点击“开始扫描导入”创建第一批数据。");
      } catch (error) {
        body.innerHTML = emptyRow(8, "读取失败", escapeHtml(error.message));
      }
    }

    function renderAiAnalysis(result) {
      if (!result?.analysis) return;
      const analysis = result.analysis;
      const insightColumn = (title, items) => `<section class="ai-column"><h3>${title}</h3>${(items || []).map((item) => `<div class="ai-insight"><strong>${escapeHtml(item.title)}</strong><p>${escapeHtml(item.evidence)}</p><p>${escapeHtml(item.recommendation)}</p></div>`).join("") || `<div class="empty-state"><strong>暂无项目</strong></div>`}</section>`;
      const actions = [...(analysis.actions || [])].sort((a, b) => number(a.priority) - number(b.priority));
      $("#aiAnalysisBody").innerHTML = `
        <div class="ai-summary">${escapeHtml(analysis.summary || "分析完成。")}</div>
        <div class="ai-meta">${escapeHtml(result.provider)} / ${escapeHtml(result.model)} · ${result.cached ? "复用历史缓存" : "本次新调用"} · Token ${integer(result.totalTokens)} · ${result.costCredits > 0 ? `<span class="ai-cost">本次费用 积分 ${integer(result.costCredits)} / ¥${number(result.costRmb).toFixed(2)}</span>` : "费用未知（历史缓存）"}</div>
        <div class="ai-grid">${insightColumn("机会", analysis.opportunities)}${insightColumn("风险", analysis.risks)}</div>
        <div class="ai-actions">${actions.map((item) => `<div class="ai-action"><span class="ai-action-priority">P${integer(item.priority)}</span><strong>${escapeHtml(item.target)}</strong><div><strong>${escapeHtml(item.action)}</strong><p>${escapeHtml(item.reason)}</p></div></div>`).join("") || `<div class="empty-state"><strong>没有新增动作</strong>继续执行本地规则建议。</div>`}</div>`;
    }

    async function loadAiStatus() {
      const badge = $("#aiStatusBadge");
      const button = $("#runAiButton");
      try {
        const response = await fetch("/api/ai/status");
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        state.aiStatus = payload.data;
        const ready = payload.data.configured;
        badge.textContent = ready ? `${payload.data.model} 已就绪` : payload.data.enabled ? "缺少 API Key" : "默认关闭";
        badge.className = `status-badge ${ready ? "scale" : "collect"}`;
        button.disabled = !ready;
        button.title = ready ? "仅发送聚合指标并手工触发" : "在上方「配置 KIE」中设置 Key 并启用";
      } catch (error) {
        badge.textContent = "状态读取失败";
        badge.className = "status-badge reduce";
        button.disabled = true;
      }
    }

    async function loadLatestAiAnalysis() {
      try {
        const response = await fetch("/api/ai/analysis/latest");
        const payload = await response.json();
        if (response.ok && payload.success && payload.data) renderAiAnalysis(payload.data);
      } catch (_) {
        // 历史结果读取失败不影响规则分析页面。
      }
    }

    async function loadAiConfig() {
      const hint = $("#aiConfigHint");
      try {
        const response = await fetch("/api/ai/config");
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        const cfg = payload.data;
        $("#aiEnabled").checked = !!cfg.enabled;
        $("#aiApiKey").value = "";
        $("#aiBaseUrl").value = cfg.baseUrl || "";
        $("#aiModel").value = cfg.model || "";
        hint.textContent = cfg.hasKey ? `当前 Key：${cfg.maskedKey}（yml 配置，界面临时修改重启还原）` : "尚未配置 Key（在 application.yml 设置）";
      } catch (error) {
        hint.textContent = "配置读取失败";
      }
    }

    function setupAiConfig() {
      $("#aiConfigToggle").addEventListener("click", () => {
        const form = $("#aiConfigForm");
        form.hidden = !form.hidden;
      });
      $("#aiConfigSave").addEventListener("click", async () => {
        const msg = $("#aiConfigMsg");
        msg.textContent = "保存中…";
        const body = {
          enabled: $("#aiEnabled").checked,
          apiKey: $("#aiApiKey").value === "" ? null : $("#aiApiKey").value,
          baseUrl: $("#aiBaseUrl").value.trim(),
          model: $("#aiModel").value.trim(),
        };
        try {
          const response = await fetch("/api/ai/config", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          });
          const payload = await response.json();
          if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
          await loadAiConfig();
          await loadAiStatus();
          msg.textContent = "已临时保存（重启还原）";
          showToast("KIE 配置已临时保存（重启后还原为 application.yml 配置）。");
        } catch (error) {
          msg.textContent = `保存失败：${error.message}`;
          showToast(`保存失败：${error.message}`);
        }
      });
      $("#aiConfigClear").addEventListener("click", async () => {
        if (!window.confirm("确认清空当前 KIE API Key？仅影响本次会话，重启后还原为 application.yml 配置。")) return;
        const msg = $("#aiConfigMsg");
        msg.textContent = "清空中…";
        try {
          const response = await fetch("/api/ai/config", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ clearApiKey: true }),
          });
          const payload = await response.json();
          if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
          await loadAiConfig();
          await loadAiStatus();
          msg.textContent = "Key 已清空（重启还原）";
          showToast("KIE API Key 已临时清空（重启后还原）。");
        } catch (error) {
          msg.textContent = `清空失败：${error.message}`;
          showToast(`清空失败：${error.message}`);
        }
      });
    }

    async function runAiAnalysis() {
      const button = $("#runAiButton");
      button.disabled = true;
      button.textContent = "Kie 分析中…";
      $("#aiAnalysisBody").innerHTML = `<div class="empty-state"><strong>正在调用 Kie AI</strong>仅发送 ${analysisPeriodLabel()} 的聚合指标，请稍候。</div>`;
      try {
        const response = await fetch(`/api/ai/analysis?${analysisQuery()}`, { method: "POST" });
        const payload = await response.json();
        if (!response.ok || !payload.success) throw new Error(payload.message || `HTTP ${response.status}`);
        renderAiAnalysis(payload.data);
        showToast(payload.data.cached ? "已复用相同数据的 AI 分析缓存。" : "Kie AI 分析完成。");
      } catch (error) {
        $("#aiAnalysisBody").innerHTML = `<div class="empty-state"><strong>AI 分析失败</strong>${escapeHtml(error.message)}</div>`;
        showToast(`AI 分析失败：${error.message}`);
      } finally {
        button.disabled = !state.aiStatus?.configured;
        button.textContent = "运行 Kie AI 分析";
      }
    }

    async function exportRecommendations() {
      let data;
      try {
        const response = await fetch(`/api/decision/analysis?periodDays=${state.periodDays}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        data = payload.data;
      } catch (error) {
        showToast(`导出失败：${error.message}`);
        return;
      }
      const rows = (data.rows || []).filter((row) => row.decision !== "collect");
      const header = ["分析周期", "广告活动", "父SKU", "广告类型", "广告费USD", "归因销售USD", "广告订单", "ROAS", "CPC", "建议", "判断依据"];
      const csvRows = rows.map((row) => [
        analysisPeriodLabel(), row.campaign, row.sku, row.type, row.spend.toFixed(2), row.adSales.toFixed(2), Math.round(row.adOrders), row.roas.toFixed(2), row.cpc.toFixed(2), decisionCopy[row.decision].label, decisionCopy[row.decision].reason,
      ]);
      const csv = [header, ...csvRows].map((row) => row.map((cell) => `"${String(cell ?? "").replaceAll('"', '""')}"`).join(",")).join("\r\n");
      const blob = new Blob(["\ufeff", csv], { type: "text/csv;charset=utf-8" });
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      const range = activePeriodRange();
      link.download = `PNK-Walmart-广告建议-${range.startDate}-${range.endDate}.csv`;
      link.click();
      URL.revokeObjectURL(link.href);
      showToast(`已导出 ${rows.length} 条建议，未执行任何广告变更。`);
    }

    $$(".nav-button").forEach((button) => button.addEventListener("click", () => switchView(button.dataset.view)));
    $$('[data-jump]').forEach((button) => button.addEventListener("click", () => switchView(button.dataset.jump)));
    // 需求预测工作台已重做，旧的需求预测搜索/排序绑定已移除（元素不再存在）。
    $$("[data-period]").forEach((button) => button.addEventListener("click", () => {
      closeCustomPeriod();
      applyPeriod(number(button.dataset.period));
    }));
    $("#salesSearch").addEventListener("input", (event) => {
      state.salesSearch = event.target.value;
      state.salesPage = 0;
      renderSalesTable();
    });
    $$("#salesGroupControls [data-sales-group]").forEach((button) => button.addEventListener("click", () => {
      state.salesGroup = button.dataset.salesGroup;
      state.salesPage = 0;
      renderSalesTable();
    }));
    $$("#salesStatusFilters [data-sales-status]").forEach((button) => button.addEventListener("click", () => {
      state.salesStatus = button.dataset.salesStatus;
      state.salesPage = 0;
      renderSalesTable();
    }));
    $$("#salesRankMetric [data-sales-rank]").forEach((button) => button.addEventListener("click", () => {
      state.salesRankMetric = button.dataset.salesRank;
      renderSalesRanking();
    }));
    $$(".sales-sort-button").forEach((button) => button.addEventListener("click", () => {
      const key = button.dataset.salesSort;
      if (state.salesSort === key) state.salesSortDirection = state.salesSortDirection === "desc" ? "asc" : "desc";
      else {
        state.salesSort = key;
        state.salesSortDirection = "desc";
      }
      state.salesPage = 0;
      renderSalesTable();
    }));
    $("#salesPrevPage").addEventListener("click", () => {
      state.salesPage = Math.max(0, state.salesPage - 1);
      renderSalesTable();
    });
    $("#salesNextPage").addEventListener("click", () => {
      state.salesPage += 1;
      renderSalesTable();
    });
    $("#campaignSearch").addEventListener("input", (event) => { state.campaignSearch = event.target.value; renderDiagnosis(); });
    $$("#campaignFilters .filter-button").forEach((button) => button.addEventListener("click", () => {
      state.campaignFilter = button.dataset.filter;
      $$("#campaignFilters .filter-button").forEach((item) => item.classList.toggle("active", item === button));
      renderDiagnosis();
    }));
    $("#inventoryTargetDays").addEventListener("change", (event) => {
      state.inventoryTargetDays = number(event.target.value);
      state.inventoryPage = 0;
      loadInventory();
    });
    // 搜索框：防抖 + 搜索态自动展开匹配款号、清空恢复用户原展开态
    let inventorySearchTimer = null;
    const inventorySearchInput = $("#inventorySearch");
    inventorySearchInput.addEventListener("input", (event) => {
      const raw = event.target.value;
      const hadQuery = (state.inventorySearch || "").trim() !== "";
      clearTimeout(inventorySearchTimer);
      inventorySearchTimer = setTimeout(() => {
        const hasQuery = raw.trim() !== "";
        if (hasQuery && !hadQuery) {
          // 进入搜索态：保存当前展开态，并把匹配款号全部展开，直接看到命中 SKU
          state.inventorySearchSavedExpanded = new Set(state.inventoryByStyleExpanded);
          const matched = inventoryMatchedParentSkus();
          state.inventoryByStyleExpanded = matched ? new Set(matched) : new Set();
        } else if (!hasQuery && hadQuery) {
          // 退出搜索态：还原用户原来的展开态
          state.inventoryByStyleExpanded = state.inventorySearchSavedExpanded || new Set();
          state.inventorySearchSavedExpanded = null;
        }
        state.inventorySearch = raw;
        if (state.inventory) renderInventoryByStyle(state.inventory.rows);
      }, 150);
    });
    $$("#inventoryFilters .filter-button").forEach((button) => button.addEventListener("click", () => {
      state.inventoryRisk = button.dataset.inventoryRisk;
      $$("#inventoryFilters .filter-button").forEach((item) => item.classList.toggle("active", item === button));
      if (state.inventory) renderInventoryByStyle(state.inventory.rows);
    }));
    $$("#inventoryByStyleHead th.bystyle-sortable").forEach((th) => th.addEventListener("click", () => {
      const key = th.dataset.sort;
      const cur = state.inventoryByStyleSort;
      if (cur.key === key) cur.dir = cur.dir === "asc" ? "desc" : "asc";
      else { cur.key = key; cur.dir = key === "risk" || key === "parentSku" ? "asc" : "desc"; }
      if (state.inventory) renderInventoryByStyle(state.inventory.rows);
    }));
    $$("#inventoryTrendRanges [data-inventory-range]").forEach((button) => button.addEventListener("click", () => {
      state.inventoryTrendRange = button.dataset.inventoryRange;
      if (state.inventory) renderInventoryTrend(state.inventory.trend);
    }));
    $$("#inventoryTrendLegend [data-inventory-series]").forEach((button) => button.addEventListener("click", () => {
      const key = button.dataset.inventorySeries;
      if (state.inventoryTrendSeries.has(key) && state.inventoryTrendSeries.size === 1) {
        showToast("库存曲线至少保留一个显示项目");
        return;
      }
      if (state.inventoryTrendSeries.has(key)) state.inventoryTrendSeries.delete(key);
      else state.inventoryTrendSeries.add(key);
      if (state.inventory) renderInventoryTrend(state.inventory.trend);
    }));
    let inventoryTrendResizeRaf = 0;
    let inventoryTrendObservedWidth = 0;
    function scheduleInventoryTrendRender() {
      cancelAnimationFrame(inventoryTrendResizeRaf);
      inventoryTrendResizeRaf = requestAnimationFrame(() => {
        inventoryTrendResizeRaf = requestAnimationFrame(() => {
          const box = document.getElementById("inventoryTrend");
          if (state.inventory && state.view === "inventory" && box && box.clientWidth > 0) {
            renderInventoryTrend(state.inventory.trend);
          }
        });
      });
    }
    window.addEventListener("resize", scheduleInventoryTrendRender);
    const inventoryTrendBox = document.getElementById("inventoryTrend");
    if (inventoryTrendBox && typeof ResizeObserver !== "undefined") {
      const inventoryTrendObserver = new ResizeObserver((entries) => {
        const nextWidth = Math.round(entries[0]?.contentRect?.width || 0);
        if (nextWidth > 0 && nextWidth !== inventoryTrendObservedWidth) {
          inventoryTrendObservedWidth = nextWidth;
          scheduleInventoryTrendRender();
        }
      });
      inventoryTrendObserver.observe(inventoryTrendBox);
    }
    let trendResizeRaf = 0;
    window.addEventListener("resize", () => {
      cancelAnimationFrame(trendResizeRaf);
      trendResizeRaf = requestAnimationFrame(() => {
        if (DATA.daily?.length) renderTrend();
      });
    });
    $("#productSearch").addEventListener("input", (event) => {
      state.productSearch = event.target.value;
      state.productPage = 0;
      renderProductCatalog();
    });
    $("#productTagFilters").addEventListener("click", (event) => {
      const button = event.target.closest("[data-product-tag-filter]");
      if (!button) return;
      state.productTagFilter = button.dataset.productTagFilter;
      state.productPage = 0;
      renderProductCatalog();
    });
    $("#productPrevPage").addEventListener("click", () => {
      state.productPage = Math.max(0, state.productPage - 1);
      renderProductCatalog();
    });
    $("#productNextPage").addEventListener("click", () => {
      state.productPage += 1;
      renderProductCatalog();
    });
    let operationSearchTimer;
    $("#operationTargetType").addEventListener("change", renderOperationTargetOptions);
    $("#operationForm").addEventListener("submit", saveOperation);
    $("#refreshOperationsButton").addEventListener("click", loadOperations);
    $("#operationSearch").addEventListener("input", (event) => {
      state.operationSearch = event.target.value.trim();
      clearTimeout(operationSearchTimer);
      operationSearchTimer = setTimeout(loadOperations, 260);
    });
    $("#closeProductionOrderDrawer").addEventListener("click", () => $("#productionOrderDrawer").close());
    $("#closeForecastDetailDrawer").addEventListener("click", () => $("#forecastDetailDrawer").close());
    $("#productionOrderSearch").addEventListener("input", (event) => {
      state.productionOrderQuery = event.target.value;
      renderProductionOrders(state.productionOrderQuery || "");
    });
    $("#inventoryMetricGrid").addEventListener("click", (event) => {
      const el = event.target.closest('[data-metric]');
      if (!el) return;
      if (el.dataset.metric === "production") openProductionOrderDrawer();
      else if (el.dataset.metric === "forecast") openForecastDetailDrawer();
      else if (el.dataset.metric === "transit") openTransitInventoryDetails();
    });
    $("#inventoryMetricGrid").addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      const el = event.target.closest('[data-metric]');
      if (!el) return;
      event.preventDefault();
      if (el.dataset.metric === "production") openProductionOrderDrawer();
      else if (el.dataset.metric === "forecast") openForecastDetailDrawer();
      else if (el.dataset.metric === "transit") openTransitInventoryDetails();
    });
    $("#transitChannelLeadTime").addEventListener("click", (event) => {
      const button = event.target.closest("[data-save-transit-channel]");
      if (!button) return;
      saveTransitChannelLeadTime(number(button.dataset.saveTransitChannel), button);
    });
    $("#transitShipmentParentFilter").addEventListener("input", (event) => {
      state.transitShipmentParentQuery = event.target.value;
      renderTransitInventory(state.inventory?.transit);
    });
    $("#inventoryByStyleBody").addEventListener("click", (event) => {
      const cell = event.target.closest("[data-transit-parent]");
      if (!cell) return;
      openTransitInventoryDetails(cell.dataset.transitParent);
    });
    $("#toggleTransitChannelLeadTime").addEventListener("click", () => {
      setTransitChannelLeadTimeCollapsed(!$("#transitChannelLeadTimeContent").hidden);
    });
    $("#transitShipmentList").addEventListener("click", (event) => {
      const clearBtn = event.target.closest("[data-clear-bucket]");
      if (clearBtn) {
        state.transitBucketFilter = "";
        renderTransitInventory(state.inventory?.transit);
        return;
      }
      const bucket = event.target.closest("[data-bucket]");
      if (!bucket) return;
      const key = bucket.dataset.bucket;
      state.transitBucketFilter = state.transitBucketFilter === key ? "" : key;
      renderTransitInventory(state.inventory?.transit);
    });
    $("#transitShipmentList").addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      const bucket = event.target.closest("[data-bucket]");
      if (!bucket) return;
      event.preventDefault();
      const key = bucket.dataset.bucket;
      state.transitBucketFilter = state.transitBucketFilter === key ? "" : key;
      renderTransitInventory(state.inventory?.transit);
    });
    $("#forecastWorkbenchSearch").addEventListener("input", (event) => {
      state.forecastWorkbenchSearch = event.target.value;
      renderDemandForecast();
    });
    $("#forecastWorkbenchFilters").addEventListener("click", (event) => {
      const button = event.target.closest("[data-forecast-filter]");
      if (!button) return;
      state.forecastWorkbenchFilter = button.dataset.forecastFilter;
      renderDemandForecast();
    });
    $("#forecastDecisionSignals").addEventListener("click", (event) => {
      const button = event.target.closest("[data-forecast-filter]");
      if (!button) return;
      state.forecastWorkbenchFilter = button.dataset.forecastFilter;
      renderDemandForecast();
      $("#forecastWorkbenchTitle").scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
    });
    $("#forecastWorkbenchSort").addEventListener("change", (event) => {
      state.forecastWorkbenchSort = event.target.value;
      renderDemandForecast();
    });
    $("#recalculateForecast").addEventListener("click", async (event) => {
      const input = $("#forecastTargetDays");
      const targetDays = Number(input.value);
      if (!Number.isInteger(targetDays) || targetDays < 7 || targetDays > 180) {
        showToast("目标覆盖天数必须是 7 到 180 之间的整数");
        input.focus();
        return;
      }
      const button = event.currentTarget;
      const previousDays = state.inventoryTargetDays;
      state.inventoryTargetDays = targetDays;
      button.disabled = true;
      button.textContent = "计算中";
      try {
        const planResponse = await fetch("/api/forecast/recommendations/recalculate", { method: "POST" });
        const planPayload = await planResponse.json();
        if (!planResponse.ok || !planPayload.success) {
          throw new Error(planPayload.message || `HTTP ${planResponse.status}`);
        }
        state.replenishmentPlan = planPayload.data || null;
        await fetchInventoryData();
        renderDemandForecast();
        showToast(`已按 ${targetDays} 天目标重新计算补货建议`);
      } catch (error) {
        state.inventoryTargetDays = previousDays;
        input.value = previousDays;
        showToast(`重新计算失败：${error.message}`);
      } finally {
        button.disabled = false;
        button.textContent = "重新计算建议";
      }
    });
    $("#forecastTargetDays").addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        $("#recalculateForecast").click();
      }
    });
    $("#inventoryForecastCompare").addEventListener("click", (event) => {
      if (event.target.closest('[data-metric="forecast"]')) openForecastDetailDrawer();
    });
    $("#inventoryForecastCompare").addEventListener("keydown", (event) => {
      if ((event.key === "Enter" || event.key === " ") && event.target.closest('[data-metric="forecast"]')) {
        event.preventDefault();
        openForecastDetailDrawer();
      }
    });
    $("#inventoryForecastCompare").addEventListener("click", (event) => {
      if (event.target.closest('[data-workbench-link]')) {
        event.stopPropagation();
        applyView("demand-forecast");
      }
    });
    $("#closeProductDrawer").addEventListener("click", () => $("#productDrawer").close());
    $("#closeTransitDrawer").addEventListener("click", () => $("#transitDrawer").close());
    // 全站图片放大：事件委托，命中缩略图/主图即弹层查看（点击图片本身不关闭，点击其余区域关闭）
    document.addEventListener("click", (event) => {
      const thumb = event.target.closest(".product-thumb, .product-hero-image, .variant-image, .inventory-thumb, .sales-thumb, .sales-rank-thumb");
      if (!thumb) return;
      const img = thumb.tagName === "IMG" ? thumb : thumb.querySelector("img");
      if (!img || !img.getAttribute("src")) return;
      event.preventDefault();
      event.stopPropagation();
      openImageLightbox(img.src, img.alt || "");
    });
    const imageLightboxDialog = $("#imageLightbox");
    if (imageLightboxDialog) {
      imageLightboxDialog.addEventListener("click", (event) => {
        if (event.target.id === "imageLightboxImg") return;
        closeImageLightbox();
      });
      imageLightboxDialog.addEventListener("close", () => {
        if (lightboxReturnDrawer && lightboxReturnDrawer.parentSku) {
          const sku = lightboxReturnDrawer.parentSku;
          lightboxReturnDrawer = null;
          openProductDrawer(sku);
        } else {
          lightboxReturnDrawer = null;
        }
      });
    }
    $("#menuButton").addEventListener("click", () => { const open = document.body.classList.toggle("sidebar-open"); $("#menuButton").setAttribute("aria-expanded", String(open)); });
    $("#mobileScrim").addEventListener("click", () => { document.body.classList.remove("sidebar-open"); $("#menuButton").setAttribute("aria-expanded", "false"); });
    $("#exportButton").addEventListener("click", exportRecommendations);
    initializeCustomPeriodControl();
    initializeImportControls();
    bindProfitCalculator();
    $("#scanImportButton").addEventListener("click", runImport);
    $("#scanImportPageButton").addEventListener("click", runImport);
    $("#refreshBatchesButton").addEventListener("click", loadBatches);
    $("#runAiButton").addEventListener("click", runAiAnalysis);
    $("#recalculateButton").addEventListener("click", saveDecisionConfig);
    $$('[data-decision-type]').forEach((button) => button.addEventListener("click", () => {
      decisionConfigType = button.dataset.decisionType;
      applyDecisionConfigToForm();
    }));

    loadDecisionConfig();
    $("#sidebarSourceTime").textContent = `最新业务日 ${DATA.meta.latestDate}`;
    $("#operationOccurredAt").value = localDateTimeValue();
    loadBatches();
    loadSales();
    loadInventory();
    loadProducts();
    loadOperationTargets();
    loadOperations();
    loadRecent();
    $$("#view-recent .diagnosis-mode-button").forEach((btn) => btn.addEventListener("click", () => {
      state.recentDays = number(btn.dataset.days);
      $$("#view-recent .diagnosis-mode-button").forEach((b) => b.classList.toggle("active", b === btn));
      loadRecent();
    }));
    loadAiStatus();
    loadLatestAiAnalysis();
    setupAiConfig();
    loadAiConfig();
    initBoxConvert();
    renderAll();
    const requestedView = location.hash.replace("#", "");
    if (titles[requestedView]) switchView(requestedView);
  