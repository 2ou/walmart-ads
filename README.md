# Walmart Ads Decision Console

面向 PNK 个人使用的 Walmart 广告决策台。项目独立运行，但包边界、Java 版本和 Spring Boot 技术栈均为后续合并进 `mint` 做了准备。

## 第一版范围

- 一键扫描 `D:\PNK\17DATA`
- 导入订单、自动广告、手动关键词广告、库存快照和商品上传资料
- 原文件归档，结构化数据写入独立 MySQL 数据库
- 导入批次、文件指纹、重复拦截和历史版本
- 广告、订单、SKU 和广告活动指标
- 基于规则的放量、降耗、优化和观察建议
- 库存总览、风险分层、历史快照和 SKU 补货明细
- 独立显示沃尔玛系统建议补货与本地预测建议补货
- 服装产品库以父 SKU 标注产品组主体，按颜色归集图片，并在颜色组内展示尺码 SKU
- Kie AI 分析接口；默认关闭，仅手工触发，不产生自动调用费用

## 数据目录约定

```text
D:\PNK\17DATA
├─ date\
│  └─ 2026-7-22\
│     ├─ PO_Data*.xlsx               # 订单；只取 Order Date 等于目录日期的行
│     ├─ ItemPerformance*.csv        # 全部广告的商品级经营汇总
│     ├─ ItemKeywordPerformance*.csv # 商品 × 搜索词明细；可含自动和手动活动
│     └─ KeywordPerformance*.csv     # 手动关键词明细
├─ source\inventory\inventory-*.xlsx # 库存快照
├─ source\upload\*-upload.xlsx       # 商品资料
└─ archive\                          # 系统归档
```

订单和广告日报统一放在 `date\日期目录` 下。广告 CSV 没有业务日期，系统使用
它所在的日期目录。订单文件可能包含多天数据，
系统只导入 `Order Date` 等于目录日期的记录。自动/手动类型以每行
`Campaign Type` 为准，不根据文件名猜测。

驾驶舱的广告费、归因销售、订单和 ROAS 只使用 `ItemPerformance` 汇总，
避免与关键词明细重复计算。`ItemKeywordPerformance` 和
`KeywordPerformance` 会完整保存在 MySQL，供后续搜索词发现和关键词优化使用。

## MySQL

先创建独立数据库：

```sql
CREATE DATABASE walmart_ads
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

通过环境变量提供连接信息，不在源码中保存密码：

```powershell
$env:WALMART_ADS_DB_URL = 'jdbc:mysql://127.0.0.1:3306/walmart_ads?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:WALMART_ADS_DB_USERNAME = 'root'
$env:WALMART_ADS_DB_PASSWORD = 'your-password'
```

## 构建与启动

```powershell
D:\Tools\apache-maven-3.9.13\bin\mvn.cmd `
  -s D:\Tools\apache-maven-3.9.13\conf\settings.xml `
  -Dmaven.repo.local=D:\Tools\apache-maven-3.9.13\repo `
  clean test package

java -jar target\walmart-ads-1.0.0-SNAPSHOT.jar
```

浏览器访问 `http://127.0.0.1:10020`。

## Kie AI 分析

AI 使用 Kie 的 OpenAI 风格 Chat Completions 接口，默认模型为低成本的
`gemini-2.5-flash`。系统先在本地完成指标聚合和规则判断，只把最多 30 天、
25 个广告活动和 20 个父 SKU 的聚合结果交给模型，原始订单和客户信息不会发送。

默认不开启。需要时设置：

```powershell
$env:WALMART_ADS_AI_ENABLED = 'true'
$env:KIE_API_KEY = 'your-kie-api-key'
```

可选配置：

```powershell
$env:KIE_MODEL = 'gemini-2.5-flash'
$env:KIE_BASE_URL = 'https://api.kie.ai'
```

分析只能通过页面按钮或 `POST /api/ai/analysis?days=7` 手工触发。相同数据、
模型和提示词版本会复用 MySQL 中的历史结果；使用 `force=true` 才会重新调用。
API Key 只从环境变量读取，不会写入源码、数据库或前端。

## 库存与预测补货

库存模块以最新库存快照为当前状态，并保留每次导入快照用于趋势查看：

- 系统建议补货：直接读取沃尔玛报表 `Suggested units`
- 路途库存：读取沃尔玛报表 `Inbound units`
- 做货库存：在库存页面手工维护，每次修改写入独立历史记录
- 预测建议补货：`ceil(max(0, 预测日均销量 × 目标覆盖天数 - 可售库存 - 做货库存 - 路途库存))`

预测日均销量结合所选周期的实际订单销量和沃尔玛报表 `Daily units sold`。
销量样本不足 7 天时会降低近期销量权重，并在页面标记低置信度。目标覆盖天数
可在页面选择 30、45、60 或 90 天，所有预测都由本地规则计算，不调用 AI。

## 产品库

系统扫描 `source\upload\*-upload.xlsx` 的 `Product Content And Site Exp*`
工作表，识别技术字段行中的 `sku`，并保存常用结构化字段和完整原始 JSON。
产品库以父 SKU 作为产品组主体。目录展示主图、Listing 标题、商品类型、上传表
参考价、颜色组、尺码、图片数量和完整度；详情抽屉先按颜色归集主图与副图，
切换颜色时同步切换对应图片，并只展示该颜色下的尺码和 SKU。主体色、主变体、
描述、Key Features、商品属性和完整原始上传字段一并保留。

上传表中的价格仅用于核对产品资料，不作为实际售价，也不参与销售额、利润、
广告效果或补货计算。实际经营指标以订单、广告和库存数据为准。

查询接口：

- `GET /api/products/catalog`：父款目录与覆盖率汇总
- `GET /api/products/{parentSku}`：Listing、产品组主体、颜色图片组和尺码 SKU 详情

产品资料只在扫描导入时更新，历史上传版本仍保留在 MySQL；产品库默认读取
每个 SKU 的当前有效版本。解析和完整度计算均为本地规则，不调用 AI。

## 隐私

订单源文件中的客户姓名、电话和完整地址不会写入数据库。源文件仅在本机归档，数据库只保存广告分析需要的订单、商品和履约字段。

## 历史统计表

一键扫描会同时读取历史工作簿
`D:\PNK\12每日销售数量统计\PNK 每日销售数据统计.xlsx`：

- `当天销量` 按订单行导入，保留订单日期、SKU、父 SKU、数量、单价和履约字段；
- `当天广告数据` 按商品广告行导入，并统一标记为自动广告的 `ITEM_PERFORMANCE`；
- 默认只导入 `2026-07-21` 之前的数据，避免与 `date\2026-7-21` 及之后的标准日报重复；
- 原工作簿会随导入批次归档，内容指纹相同会跳过；工作簿更新后重新导入时，旧版本保留但不再参与当前汇总。

路径和截止日期可分别通过 `WALMART_ADS_HISTORICAL_WORKBOOK` 与
`WALMART_ADS_HISTORICAL_END_DATE_EXCLUSIVE` 调整。

## SKU、Item ID 与 UPC 映射

一键扫描会读取
`D:\PNK\02后台文件\01 WFS发货计划\WFS上传模板.xlsx` 的 `Sheet1`，
把 SKU、父 SKU、Item ID、GTIN、UPC 和 Item Name 保存到独立映射表。
相同内容通过文件指纹跳过；模板更新后会保留旧版本，并把新版本标记为当前映射。

映射导入后会自动补齐现有广告记录中可匹配的 SKU/父 SKU，以及订单记录中缺失的
UPC。后续广告 CSV 没有 SKU、只有 Item ID 时，也会优先从当前 WFS 映射解析。
可通过 `WALMART_ADS_SKU_MAPPING_WORKBOOK` 调整模板路径。
