# 沃尔玛选品中心采集

首批候选池可以先导入榜单页返回的商品，然后补充详情和 SKU 快照。工作台数据按监测来源共享，采集日期按北京时间。首批候选导入不会生成销量。

1. 在 Ego 的任务空间打开监测链接。确认 Walmart 页面上的邮编等于任务邮编。
2. 通过 `ego-browser nodejs` 运行脚本，向 `captureListing({page, sourceUrl, zip, outputFile})` 传入该浏览器页和绝对输出路径。`capture-listing.mjs` 从第一页识别分页数，独立核对每页。遇到限流或验证就停。冷却结束或用户完成验证后可使用 `resume: true` 补采同一批次的缺页。来源配置或首屏顺序已经变化时另建批次。
3. 可用 `capture-products.mjs` 的 `captureProducts({page, listingPath, limit, concurrency})` 分批补充父子体与变体清单。款式页只填与所请求 Item ID 对应的可售上限。运行记录保存在与列表相同的目录。
4. 全量库存批次通过 `capture-quantities.mjs` 的 `captureQuantities({page, listingPath, productsPath, limit, concurrency})` 读取剩余 SKU。只合并同一批次日期的核实结果。商品页请求返回限流时，按返回的重试时间暂停。
5. 在项目根目录用 `node scripts/selection-center/prepare-import.mjs <listing.json> <out.json> [products.json] [quantities.json]` 生成工作台导入文件。打开「数据中心 → 文件补录」导入。

完整的销量比较需要相邻两个日期、同一监测口径下的库存快照。每天的候选页覆盖与 SKU 覆盖分别核对。商品资料、库存缺失与数量为零在工作台分别显示。

### 同一采集日期分批续跑

使用 `capture-catalog.mjs` 的 `captureCatalog({page, sourceUrl, zip, outputDir, timeBudgetMs})`，传入已经打开对应 Walmart 榜单的 Ego 页面。给每个采集日期分配独立的绝对输出目录。同一目录一次运行一个采集进程，后续调用沿用该目录，从尚未完成的阶段继续。已有 `listing.json` 时可以省略 `sourceUrl` 和 `zip`。

函数返回 `stage` 和 `status`。`pending` 表示还有工作，`restricted` 表示 Walmart 暂停了访问，`incomplete` 表示已处理可用结果但仍有缺失，`complete` 表示候选页、款式、变体和 SKU 可售量全部核实。HTTP 429 等待 `Retry-After`。缺少该头时等待至少两分钟。确认正常访问已经恢复后才能以 `resumeAfterAccessRestored: true` 续跑。跨过北京时间当天后，下次采集使用新目录。
