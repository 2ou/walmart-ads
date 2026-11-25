# AGENTS.md — Walmart Workbench (`walmart-ads`) UI Architecture & Coding Rules

This repository is a Spring Boot Java application (`8081` port) whose UI (`src/main/resources/static/`) is being progressively modernized from monolithic vanilla DOM scripts in `index.html` to an **Apple HIG / macOS style React 18 + TypeScript + Vite + HeroUI (`@heroui/react`, primary visual & interaction library) + Ant Design 5 (complex ERP table supplement)** module located in `frontend/`.

---

## 1. Frontend Build & Directory Structure

- **Frontend workspace**: `frontend/`
- **Build command** (Windows PowerShell — always use `npm.cmd`, never `npm`, and keep cache on D drive):
  ```powershell
  npm.cmd --prefix frontend install --cache D:\GeminiData\_cache\npm
  npm.cmd --prefix frontend run build
  ```
- **Build output**: Vite compiles `frontend/src/main.tsx` directly into:
  - `src/main/resources/static/dist/workbench-ui.js`
  - `src/main/resources/static/dist/workbench-ui.css`
  (`emptyOutDir: true` cleans only `static/dist/`, never touching `index.html`, `app.css`, `finance.js`, `returns.js`, etc.)
- Spring Boot continues to serve `src/main/resources/static/` on port `8081`. No Node server is needed at runtime.

---

## 2. Two-Tier UI Component Rules (Mandatory for AI & Developers)

To prevent duplicated tables, uploaders, filters, drawers, and CSS collisions:

1. **No Storybook**: Component discovery uses TypeScript interfaces in [`frontend/src/components/index.ts`](frontend/src/components/index.ts) and the catalog below.
2. **Do NOT import `antd/dist/reset.css` and keep Tailwind `preflight: false`**: The app runs in hybrid coexistence with legacy `app.css`. Importing AntD global CSS reset or enabling Tailwind preflight will alter legacy unmigrated pages. Keep HeroUI styled via `tailwind.config.cjs` + `<HeroUIProvider>` and Ant Design via `<ConfigProvider theme={getWorkbenchTheme(accentColor)}>`, scoping custom styles under `.wb-react-root` or `.wb-detail-drawer` in `frontend/src/workbench-ui.css`.
3. **Reuse Tier 1 + Tier 2 (HeroUI Primary + AntD Supplement)**:
   - **Tier 1 (Primitives)**:
     - **Primary (HeroUI + Framer Motion)**: Use HeroUI components re-exported via [`frontend/src/components/index.ts`](frontend/src/components/index.ts) (`HeroButton`, `HeroChip`, `HeroCard`, `HeroCardHeader`, `HeroCardBody`, `HeroSpinner`, `HeroTooltip`, `@heroui/react` Dropdown) for cards, buttons, pills, tooltips, and smooth segmented controls.
     - **Supplementary (Ant Design 5)**: Use `antd` for heavy ERP controls (`Table` with fixed columns / `Table.Summary`, `Input`, `InputNumber`, `Select`, `Descriptions`, `Drawer`, `Collapse`, `Popconfirm`, `Alert`, `Progress`) and `@ant-design/icons`. Never build custom HTML `<button>`, `<select>`, `<dialog>`, or `<table>` tags for migrated views.
   - **Tier 2 (Workbench Composites)**: Every migrated view MUST compose UI blocks from [`frontend/src/components/index.ts`](frontend/src/components/index.ts):

| Component / Export | Source | Purpose | Key Props |
| --- | --- | --- | --- |
| `PageHeaderCard` | `PageHeaderCard.tsx` | HeroUI Card + Chip top banner with eyebrow badge, title, subtitle & right action slot | `eyebrow`, `title`, `description`, `accentColor?`, `extra?` |
| `MetricCardGrid` | `MetricCardGrid.tsx` | Responsive KPI summary grid backed by HeroUI Card + Skeleton | `items: MetricCardItem[]`, `columns?` (default 4), `accentColor?` |
| `AppleSegmentedTabs<T>` | `AppleSegmentedTabs.tsx` | macOS/iOS sliding pill segmented control backed by HeroUI `Tabs` | `value`, `options`, `onChange`, `ariaLabel?` |
| `WorkbenchFilterTable<T>` | `WorkbenchFilterTable.tsx` | HeroUI filter/table Cards + HeroButton controls + high-density AntD `Table` with 0-indexed server pagination & sorting | `filterTitle`, `filterContent`, `onSearchSubmit`, `onReset`, `tableTitle`, `columns`, `dataSource`, `page`, `pageSize` (`20\|50\|100`), `total`, `onPageChange`, `onSortChange?` |
| `FileUploadDropzone` | `FileImportSuite.tsx` | Single-file CSV/XLSX drag-and-drop / click uploader with busy state & HeroUI Chip pill | `title`, `hint`, `accept?`, `accentColor?`, `uploading?`, `onUploadFile`, `onValidationError?` |
| `ImportFeedbackAlert` | `FileImportSuite.tsx` | Full-width import status Alert with expandable row-level error list | `feedback: ImportFeedbackState \| null`, `onClose?` |
| `ImportBatchPanel` | `FileImportSuite.tsx` | Collapsible HeroUI Card batch audit history table with HeroUI status Chips & Popconfirm delete | `batches`, `loading?`, `subtitle?`, `onInspectBatchErrors`, `onDeleteBatch` |
| `SkuLinkTag` | `SkuAndDetailSuite.tsx` | Displays SKU + clickable parent SKU HeroUI Dropdown (jump to Product / Inventory / Returns) | `sku?`, `parentSku?`, `matched?` |
| `RecordDetailDrawer` | `SkuAndDetailSuite.tsx` | 720px AntD Drawer rendering grouped `<Descriptions>` sections (`结构化字段` / `原始字段`) | `open`, `eyebrow?`, `title`, `subtitle?`, `sections`, `loading?`, `onClose` |
| `SyncStatusBanner` | `SyncStatusBanner.tsx` | Horizontal sync/snapshot status banner backed by HeroUI Card + Chip | `tone` (`loading\|success\|warning\|danger`), `headline`, `detail?`, `metaLabel?`, `metaValue?`, `extra?` |

### Migrated React Views & Islands (`frontend/src/views/`)
- `dashboard` (`#metricGrid`), `sales` (`#salesMetricGrid`), `products` (`#productMetricGrid`), `diagnosis` (`#diagnosisMetricGrid`, `#manualKeywordMetrics`, `#lxAdMetrics`, `#lxAdQualityMetrics`), `inventory` (`#inventoryMetricGrid`), `supply-chain` (`#supplyChainMetrics`), `demand-forecast` (`#forecastWorkbenchMetrics`), `data-center` (`#dataCenterSummary`), and `returns` (`#returnMetricGrid`) KPI Islands — `frontend/src/views/dashboard/DashboardMetrics.tsx`
- `wfs-settlement` (`#wfs-settlement-root`) — `frontend/src/views/wfs/WfsSettlementView.tsx`
- `wfs-storage` (`#wfs-storage-root`) — `frontend/src/views/wfs/WfsStorageView.tsx`
- `lingxing` (`#lingxing-root`) — `frontend/src/views/lingxing/LingxingDataView.tsx`
- `profit-estimate` (`#profit-estimate-root`) — `frontend/src/views/tools/ProfitEstimateView.tsx`
- `box-convert` (`#box-convert-root`) — `frontend/src/views/tools/BoxConvertView.tsx`
- `sku` (`#sku-root`) — `frontend/src/views/sku/SkuMatrixView.tsx`
- `operations` (`#operations-root`) — `frontend/src/views/operations/OperationsView.tsx`
- `recent` (`#recent-root`) — `frontend/src/views/recent/RecentSalesView.tsx`
- `reputation` (`#reputation-root`) — `frontend/src/views/reputation/ReputationView.tsx`
- `agent` (`#agent-root`) — `frontend/src/views/agent/AdvertisingAgentView.tsx`

---

## 3. Cross-View Communication Bridge (`window.workbenchBridge`)

React views MUST NOT import or manipulate legacy DOM nodes directly outside their mount root (`#wfs-settlement-root`, etc.). Use [`workbenchBridge`](frontend/src/bridge/workbenchBridge.ts) (`window.workbenchBridge`):

- `workbenchBridge.apiFetch<T>(url, options?)` — fetches JSON `{ success, message, data }` with `cache: 'no-store'` and throws readable errors when `!ok || !success`.
- `workbenchBridge.switchView(view, options?)` — navigates to another sidebar view.
- `workbenchBridge.openProductDashboard(parentSku)` — opens the Product Catalog detail drawer/dashboard.
- `workbenchBridge.openInventoryStyleDashboard(parentSku)` — navigates to Inventory and opens the Style Dashboard for `parentSku`.
- `workbenchBridge.openReturnsDrawer(parentSku)` — opens Returns Analysis focused on `parentSku`.
- `workbenchBridge.showToast(message)` — displays a non-blocking toast using the host toast bar.
- `workbenchBridge.getAnalysisQuery(extra?)` — returns active date-range query string (`days=...` / custom range).
- `workbenchBridge.getSkuMatrixSnapshot()` — returns aggregated SKU matrix rows & ROAS thresholds.
- `workbenchBridge.openOperationDialog(targetType, targetKey, prefill?)` — opens the Operation creation modal.
- `workbenchBridge.resolveOperationReminder(reminderId)` — resolves a review checkpoint reminder.
- `workbenchBridge.refreshOperationReminders()` — reloads header reminder badge state.

When adding a new React view `<section class="view" id="view-xxx"><div id="xxx-root" class="wb-react-root"></div></section>`:
1. Create the view component under `frontend/src/views/<domain>/` wrapped in `<ConfigProvider locale={zhCN} theme={getWorkbenchTheme(...)}>`. (`HeroUIProvider` is already provided at mount time by `main.tsx`.)
2. Mount it in `frontend/src/main.tsx` and expose a trigger on `window.workbenchReactUI` if `switchView()` needs to trigger lazy loading on tab enter.
3. Run `npm.cmd --prefix frontend run build` and verify `tsc --noEmit` passes with zero errors.
