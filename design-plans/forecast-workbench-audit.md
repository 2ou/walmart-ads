# 需求预测工作台 · UI 审计报告

> 审计方法：improve-ui（只读数、只出报告，不改源码）
> 审计面：需求预测工作台「按款号补货与因子」（`#view-forecast` 内的 `#forecastWorkbench`）
> 审计时间：2026-07-29

## Implementation status（2026-07-29 已实施）
- 用户「报告全要」授权落地全部 3 条 finding，纯前端 `index.html`，无后端 / Flyway。
- **#2 删除「SKU 数」列**（主表头 / 汇总行 / 明细子表头 / 明细数据行各 1 列）→ 总列 11→10；**#1 的 `colspan` 错位因此自然消解**（明细外层 `colspan="10"` 与删列后的 10 列主表对齐，无需再改 11）。
- **#3 副标题**改为「款号行『日均销量(手动)』与展开后各 SKU 的因子列支持双击编辑」。
- **范围守护**：另一张 `#forecastDetailBody` 采购建议表（约 2059 / 4050 行）也含「SKU 数」列，不在本次审计面内，**未改动**。
- **验证**：重新 `mvn -o clean package`，8081 起服后确认工作台 thead 与明细子表头均 10 `<th>`、无「SKU 数」、新副标题已生效。

## Design language
- **Audited surface**：`按款号补货与因子` 工作台视图——指标卡（`#forecastWorkbenchMetrics`）+ 汇总表（`#forecastWorkbenchBody`，`forecast-workbench-table`）+ 可展开的 SKU 明细（`po-order-detail` 嵌套 `po-detail-table`）。
- **Design sources**：无 `DESIGN.md`、无设计文档。本地设计系统即 `index.html` 内联 CSS 变量 + 类（`.inventory-table` / `.inventory-metric` / `.factor-cell` / `.po-order-row` / `.po-order-detail` 等）。
- **Documented decisions**：None documented。
- **Governing owners and consumers**：`forecast-workbench-table`、`forecast-workbench-metrics`、`.factor-cell`、`.po-order-row` / `.po-order-detail`、`.inventory-metric`。
- **Explicit exceptions**：None documented。

## Findings
| # | Problem | Evidence | Proposed change | Scope | Confidence |
| --- | --- | --- | --- | --- | --- |
| 1 | 展开 SKU 明细后，明细面板没有铺满 11 列表格，右边缘与「退货重复利用率」表头对不齐，右侧留出一条空列。 | `forecast-workbench-table` 表头定义了 **11 个** `<th>`（1754–1766 行）；汇总行也输出 11 个 `<td>`（3862–3874 行）。明细行 `renderWorkbenchDetail` 与汇总行同处一个 `<tbody id="forecastWorkbenchBody">`（3859–3875 行 `renderDemandForecast`），但其唯一单元格写的是 `<td colspan="10">`（**3912 行**），漏掉第 11 列。 | 把 3912 行的 `colspan="10"` 改为 `colspan="11"`。 | forecast-workbench-table 展开明细 | **High** |
| 2 | 展开后的 SKU 明细表里，「SKU 数」列每行永远显示 `1`，既冗余又容易误读（表头像在说"数量"，但每行本就是单个 SKU）。 | 明细表头 `<th class="num">SKU 数</th>`（3913 行）；每个明细体行硬编码 `<td class="num">1</td>`（**3901 行**），所有行恒为 1。 | 删除明细表中这冗余的一列：去掉 3913 行的 `<th>SKU 数</th>` 与 3901 行对应的 `<td class="num">1</td>`。（若想保留"数量"语义，可改为把表头文案改成「数量」——但值恒为 1 仍无意义，建议直接删。） | 嵌套 `po-detail-table` | **Medium** |
| 3 | 面板副标题写"双击因子单元格即可在系统内编辑"，但汇总（款号）行里只有「日均销量(手动)」一个单元格可编辑，其余 5 个因子列（季节性系数/增益天数/安全库存天数/退货率/退货重复利用率）在汇总行是只读的。 | 副标题文案在 **1750 行**；汇总行仅 `manualDailySales` 渲染为带 `data-field` 的 `.factor-cell`（3868 行），seasonal/gainDays/safetyDays/returnRate/reuseRate 是普通 `td.num`、无 `.factor-cell`（3869–3873 行）；双击处理器只激活 `.factor-cell`（3770–3773 行），故其余列在汇总行无法编辑。 | 把副标题改为准确口径，例如："双击『日均销量(手动)』按款号编辑；其余因子展开后逐 SKU 双击编辑"。 | panel-subtitle 文案 | **Medium** |

## Improve first
**#1（colspan 11）** 杠杆最高：每个展开行都会出现可见的右边距错位，且是单行改动、零行为变化、零风险。建议优先修。
#2、#3 都是低成本的文案/冗余列清理，可顺手做，但不影响功能。

## 明确排除项（非设计 finding，按技能边界不计入上表）
- **双击「日均销量(手动)」触发展开/收起而非编辑**：这是交互正确性（功能）bug，不属于 UI 设计审计范围。已在对话中单独诊断（根因：款号行整行 click = 展开/收起，与双击编辑事件冲突），待你确认后单独修复。
- 无障碍 / ARIA、性能、架构、路由等问题：未在你授权范围内，未纳入。

---
下一步：以上 3 条若你想落地，告诉我**哪几条要转成实现计划**（可答「全要」或指定编号），我再按技能第 6 步为每条写自包含的实施计划。
