# Walmart Ads Decision Console

面向 PNK 个人使用的 Walmart 广告决策台。项目独立运行，但包边界、Java 版本和 Spring Boot 技术栈均为后续合并进 `mint` 做了准备。

## 第一版范围

- 一键扫描 `D:\PNK\17DATA`
- 导入订单、自动广告、手动关键词广告、库存快照和商品上传资料
- 原文件归档，结构化数据写入独立 MySQL 数据库
- 导入批次、文件指纹、重复拦截和历史版本
- 广告、订单、SKU 和广告活动指标
- 基于规则的放量、降耗、优化和观察建议
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

## 隐私

订单源文件中的客户姓名、电话和完整地址不会写入数据库。源文件仅在本机归档，数据库只保存广告分析需要的订单、商品和履约字段。
