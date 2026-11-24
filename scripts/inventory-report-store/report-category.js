document.addEventListener("DOMContentLoaded", () => {
  if (document.getElementById("inventory-style-table")) return;
  const headings = Array.from(document.querySelectorAll("h2"));
  const summary = headings.find((heading) => heading.textContent.trim() === "品类聚合");
  const details = headings.find((heading) => heading.textContent.trim().startsWith("全款明细"));
  const summaryTable = summary?.nextElementSibling;
  const detailTable = details?.nextElementSibling;
  if (summaryTable?.tagName !== "TABLE" || detailTable?.tagName !== "TABLE") return;

  const sizeData = document.getElementById("inventory-size-groups");
  const sizeGroups = sizeData ? JSON.parse(sizeData.textContent) : {};
  const detailRows = Array.from(detailTable.tBodies[0]?.rows ?? [], (row) => ({
    row,
    category: row.querySelector(".titlecell .meta")?.textContent.match(/^[^·]+·\s*(.+)$/s)?.[1].trim(),
    size: sizeGroups[row.querySelector(".titlecell a[href]")?.getAttribute("href").match(/item-(\d+)-report\.html$/)?.[1]],
  }));
  if (!detailRows.length || detailRows.some(({ category }) => !category)) return;

  summary.id = "inventory-category-summary";
  details.id = "inventory-style-list";
  details.tabIndex = -1;
  detailTable.id = "inventory-style-table";

  const originalTitle = details.firstChild.textContent;
  const exportButton = details.querySelector(".export-btn");
  const exportLabel = exportButton?.textContent;
  const toolbar = document.createElement("div");
  toolbar.className = "inventory-category-toolbar";
  const status = document.createElement("span");
  status.className = "inventory-category-status";
  status.setAttribute("role", "status");
  const clear = document.createElement("button");
  clear.type = "button";
  clear.textContent = "查看全部款式";
  clear.setAttribute("aria-controls", detailTable.id);
  clear.hidden = true;
  const back = document.createElement("a");
  back.href = "#" + summary.id;
  back.textContent = "返回品类聚合";
  toolbar.append(status, clear, back);
  details.after(toolbar);

  const sizeLabels = new Map();
  const sizeButtons = new Map();
  const empty = document.createElement("p");
  empty.className = "inventory-size-empty";
  empty.hidden = true;
  toolbar.after(empty);
  if (sizeData) {
    sizeLabels.set("3xl", "3XL 档");
    sizeLabels.set("plus-only", "纯大码");
    const controls = document.createElement("div");
    controls.className = "inventory-size-filters";
    controls.setAttribute("role", "group");
    controls.setAttribute("aria-label", "尺码筛选");
    controls.setAttribute("aria-describedby", "inventory-size-guide");
    for (const [size, label] of [[null, "全部尺码"], ...sizeLabels]) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = label;
      button.setAttribute("aria-controls", detailTable.id);
      button.addEventListener("click", () => select({ ...selectedFromUrl(), size }, false));
      sizeButtons.set(size, button);
      controls.append(button);
    }
    const guide = document.createElement("span");
    guide.id = "inventory-size-guide";
    guide.textContent = "3XL 档：混合尺码最大到 3XL / 3X。纯大码：所有记录尺码均为 XL / 0X 及以上。";
    controls.append(guide);
    details.after(controls);
  }

  const links = new Map();
  for (const row of summaryTable.tBodies[0]?.rows ?? []) {
    const cell = row.cells[0];
    const category = cell.textContent.trim();
    const link = document.createElement("a");
    link.className = "inventory-category-link";
    link.textContent = category;
    link.setAttribute("aria-controls", detailTable.id);
    link.addEventListener("click", (event) => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      select({ ...selectedFromUrl(), category });
    });
    cell.replaceChildren(link);
    links.set(category, link);
  }

  function filterUrl({ category, size }) {
    const url = new URL(location.href);
    if (category) url.searchParams.set("category", category);
    else url.searchParams.delete("category");
    if (size) url.searchParams.set("size", size);
    else if (sizeData) url.searchParams.delete("size");
    url.hash = details.id;
    return url.pathname + url.search + url.hash;
  }

  function render({ category, size }) {
    const active = links.has(category) ? category : null;
    const activeSize = sizeLabels.has(size) ? size : null;
    let count = 0;
    for (const item of detailRows) {
      item.row.hidden = Boolean((active && item.category !== active) || (activeSize && item.size !== activeSize));
      if (!item.row.hidden) count += 1;
    }
    for (const [name, link] of links) {
      link.href = filterUrl({ category: name, size: activeSize });
      if (name === active) link.setAttribute("aria-current", "true");
      else link.removeAttribute("aria-current");
    }
    for (const [option, button] of sizeButtons) {
      button.setAttribute("aria-pressed", String(option === activeSize));
    }
    const filtered = active || activeSize;
    const resultTitle = activeSize ? "筛选款式" : "品类款式";
    details.firstChild.textContent = filtered ? originalTitle.replace("全款明细", resultTitle) : originalTitle;
    if (exportButton) exportButton.textContent = filtered ? "导出全部 ↓" : exportLabel;
    status.textContent = [active || "全部品类", sizeLabels.get(activeSize), count.toLocaleString("zh-CN") + " 款"]
      .filter(Boolean).join(" · ");
    clear.hidden = !filtered;
    empty.hidden = count > 0;
    empty.textContent = count ? "" : "当前筛选没有符合条件的款式。";
    detailTable.hidden = !count;
  }

  function select(filters, jump = true) {
    const next = filterUrl(filters);
    if (next !== location.pathname + location.search + location.hash) history.pushState(null, "", next);
    render(filters);
    if (jump) {
      details.focus({ preventScroll: true });
      details.scrollIntoView({ block: "start" });
    }
  }

  function selectedFromUrl() {
    const params = new URLSearchParams(location.search);
    return { category: params.get("category"), size: params.get("size") };
  }

  clear.addEventListener("click", () => select({ category: null, size: null }));
  window.addEventListener("popstate", () => render(selectedFromUrl()));
  render(selectedFromUrl());
  if (location.hash === "#" + details.id) details.scrollIntoView({ block: "start" });
// Published editions also work with portal versions that inject the older category handler.
}, { capture: true });
