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
- 产品库按父 SKU 汇总图片、Listing、属性和全部 SKU 变体
- Kie AI 分析接口；默认关闭，仅手工触发，不产生自动调用费用

## 数据目录约定

```text
D:\PNK\17DATA
├─ 2026-7-21\
│  ├─ PO_Data*.xlsx                  # 订单；只取 Order Date 等于目录日期的行
│  ├─ ItemPerformance*.csv           # 自动广告
│  └─ ItemKeywordPerformance*.csv    # 手动关键词广告
├─ source\inventory\inventory-*.xlsx # 库存快照
├─ source\upload\*-upload.xlsx       # 商品资料
└─ archive\                          # 系统归档
```

广告 CSV 没有业务日期，系统使用它所在的日期目录。订单文件可能包含多天数据，系统只导入 `Order Date` 等于目录日期的记录。

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
产品库按父 SKU 聚合，目录展示主图、Listing 标题、商品类型、上传表参考价、颜色、
尺码、图片数量和完整度；详情抽屉展示主图与副图、描述、Key Features、
商品属性、原始上传字段和全部 SKU 变体。

上传表中的价格仅用于核对产品资料，不作为实际售价，也不参与销售额、利润、
广告效果或补货计算。实际经营指标以订单、广告和库存数据为准。

查询接口：

- `GET /api/products/catalog`：父款目录与覆盖率汇总
- `GET /api/products/{parentSku}`：Listing、图片、属性和 SKU 变体详情

产品资料只在扫描导入时更新，历史上传版本仍保留在 MySQL；产品库默认读取
每个 SKU 的当前有效版本。解析和完整度计算均为本地规则，不调用 AI。

## 隐私

订单源文件中的客户姓名、电话和完整地址不会写入数据库。源文件仅在本机归档，数据库只保存广告分析需要的订单、商品和履约字段。
