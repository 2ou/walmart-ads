# PNK0079 广告优化操作清单

> 数据窗口：报表导出 2026-08-02（自动广告 ZDGG 自 2026-05-18 起、手动广告 SDEX/SDGF/SDPH 自 2026-06-01 起）
> 判断线：**RoAS ≥ 4 = 好（等价 ACOS ≤ 25%）**；RoAS < 4 需处理
> **出价下限（平台硬约束）：自动广告 ≥ $0.20，手动广告 ≥ $0.30。任何降 bid 不得突破此线，否则只能靠精确否定 / 暂停控面。**
> 整体健康度：自动 RoAS 4.85 / 手动 RoAS 4.04，本清单为「提效 + 收割增量」，非救火

---

## 一、手动广告（PNK0079-SDEX / SDGF / SDPH）

### A. 提 bid —— 赢家词（RoAS ≥ 4，加预算抢量）

| 关键词 | 匹配 | 当前 bid | 建议 bid | 依据 |
|---|---|---|---|---|
| travel outfit | exact | $0.35 | **$0.50** | RoAS 5.3，可加价扩量 |
| matching set summer | broad | $0.30 | **$0.45** | RoAS 8.1 |
| 2 piece travel set | broad | $0.30 | **$0.50** | RoAS 73，高潜力但当前仅花 $0.30，严重低估 |

### B. 降 bid —— 临界词（RoAS < 4，低于判断线）

| 关键词 | 匹配 | 当前 bid | 建议 bid | 依据 |
|---|---|---|---|---|
| two piece | phrase | $0.32 | **$0.30**（手动最低） | RoAS 2.4 / ACOS 42%，降一档控预算 |
| woman outfit 2 piece | broad | $0.30 | **$0.30 维持**（已手动最低） | 无法再降；靠 C 组 exact 否定控面，2 周后仍差则暂停 |
| summer outfit | phrase | $0.32 | **$0.30**（手动最低） | RoAS 2.6 / ACOS 39%，降一档控预算 |

### C. 加 exact 否定 —— 亏损词（有花费 0 订单）

**优先（浪费 ≥ $1，先加）：**
- `two piece set summer`（broad，花 $5.70 / 19 点击）
- `2 piece set`（exact，花 $4.15 / 12 点击）
- `2 piece set pants`（broad，花 $1.80）
- `matching set`（exact，花 $1.75）
- `wide leg lounge set`（broad，花 $1.50）
- `2 piece outfit summer`（broad，花 $1.50）
- `woman summer outfit`（broad，花 $1.50）
- `two piece pants set`（broad，花 $1.50）
- `summer travel outfit`（broad，花 $1.20）
- `casual matching set`（broad，花 $1.20）
- `two piece set`（exact，花 $1.05）
- `two piece outfit for woman`（broad，花 $0.90）

**其余 13 个长尾（< $0.60，一并加 exact 否定）：**
`lady travel outfit`、`vacation outfit`、`airport outfit`、`two piece lounge set`、`two piece vacation outfit`、`cute matching set`、`matching set vacation`、`two piece vacation set`、`wide leg pants woman set`、`matching set vacation`、`2 piece set summer`、`vacation 2 piece set`、`two piece matching set`

> 合计 25 个亏损词，总浪费约 $28。金额不大但加否定后预算可回流给 A 组赢家。

### D. 拆集中度 —— 新增 exact 词（分散 `2 piece` 一家独大风险）

`2 piece`(phrase) 当前独占手动 67% 花费 / 79% 销售额。建议在该手动广告组**新增以下 exact 词**，把流量结构打散：
- `2 piece outfits for women`
- `2 piece lounge set`
- `2 piece travel set`（已在 A 提 bid，此处确认归属）
- `2 piece sets for women`

起始 bid 建议 $0.35–0.45，与 A 组赢家一致。

---

## 二、自动广告（PNK0079-ZDGG，策略 Fixed / 无 Target ROAS）

> 自动整体 RoAS 4.85 健康，**保留「发现」角色，不要关**。只做两件事：收割赢家 + 加否定止损。
> ⚠️ 自动广告默认出价下限 **$0.20**（ZDGG campaign 级若调整勿低于此）；本清单不调自动出价，仅收割/否定。

### E. 收割到手动 —— 高 RoAS 搜索词 → 加为手动 exact（起始 bid $0.40）

| 自动搜索词 | 订单 | RoAS | 加为手动 |
|---|---|---|---|
| travel outfits for women 2 piece sets | 6 | 15.1 | exact $0.45 |
| travel outfits | 2 | 15.8 | exact $0.45 |
| pants outfits for women 2 piece sets | 1 | 47.0 | exact $0.40 |
| modern moments | 1 | 40.7 | exact $0.40 |
| linen sets for women 2 piece | 1 | 11.6 | exact $0.40 |
| pants sets for women | 1 | 12.5 | exact $0.40 |
| womens travel outfit | 1 | 23.0 | exact $0.40 |
| womens matching sets | 1 | 23.0 | exact $0.40 |
| cute outfits for women | 1 | 29.3 | exact $0.40 |
| sleeveless 2 piece pants set for women | 1 | 34.4 | exact $0.40 |
| scuba knit sets for women 2 piece | 2 | 8.3 | broad $0.40 |
| matching sets women outfits | 1 | 7.3 | exact $0.40 |
| pretty garden 2 piece outfit | 1 | 6.8 | exact $0.40 |
| 2 piece sets | 1 | 5.6 | exact $0.40 |

### F. 加 exact 否定 —— 自动里高浪费搜索词（≥ $1 花费 / 0 订单）

- `2 piece women outfit`（花 $4.98 / 16 点击）
- `travel outfit`（花 $2.04 / 6 点击）※ 注意：`travel outfits`(复数) 有转化，exact 否定单数安全
- `womens outfit sets`（花 $1.45 / 5 点击）
- `polka dot set womens`（花 $1.45 / 5 点击）
- `modern moments clearance`（花 $1.36 / 5 点击）※ `modern moments`(无 clearance) 有转化，exact 否定带 clearance 安全
- `womens pants sets 2 piece outfits`（花 $1.16 / 4 点击）
- `womens pant sets outfit`（花 $1.16 / 4 点击）
- `airport outfit for women`（花 $1.16 / 4 点击）
- `travel outfits for women`（花 $1.07 / 4 点击）※ `travel outfits for women 2 piece sets` 有转化，exact 否定短词安全
- `scuba knit set`（花 $1.07 / 4 点击）※ `scuba knit sets`(复数) 有转化，exact 否定单数安全
- `women linen pants sets outfits`（花 $1.07 / 4 点击）

---

## 三、执行顺序建议

1. **先加否定（C + F）** —— 止损最快，不影响在跑的赢家
2. **降 bid 临界词（B）** —— 把 RoAS<4 的预算压下来
3. **提 bid 赢家 + 新增 exact 拆分散（A + D）** —— 把省下的预算投给高效词
4. **收割自动赢家到手动（E）** —— 最后做，形成「自动发现 → 手动锁定」闭环

> 执行后建议 2–3 周回看同口径报表，复核 RoAS 是否整体抬升、亏损词是否清零。
