package com.ai.walmartads.lingxingorder;

import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.OrderRecord;
import com.ai.walmartads.importing.parser.ImportValues;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.importing.repository.OrderRecordRepository;
import com.ai.walmartads.lingxingorder.domain.LingXingOrderSyncLog;
import com.ai.walmartads.lingxingorder.repository.LingXingOrderSyncLogRepository;
import com.ai.walmartads.walmartlisting.LingXingClient;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 领星 MCP 销量/订单日粒度同步：拉取 SKU × 日的「销量 / 订单量 / 销售额」，
 * 拆行还原成 {@code wm_ads_order_record} 的行结构，写入 {@code wm_ads_import_file}(active=true)。
 *
 * <h2>为什么是拆行，而不是直接存聚合值</h2>
 * 库内 14 个服务、30+ 处 SQL 都以「join import_file.active = true + 聚合」的方式消费这张表，
 * 且没有一处依赖单行明细语义。只要写入的行满足下面三个等式，这些 SQL 全部无需改动：
 * <pre>
 *   sum(quantity)                = 销量
 *   sum(quantity * item_cost)    = 销售额
 *   count(distinct order_number) = 店铺级订单量
 * </pre>
 *
 * <h2>三个等式怎么保证</h2>
 * <ol>
 *   <li><b>销量</b>：件数按整数拆分，前 rows-1 行分掉 units-1 件，最后一行恒为 1 件，总和精确等于销量。</li>
 *   <li><b>销售额</b>：前 rows-1 行用统一单价 {@code c = round(revenue/units, 4)}，最后一行用
 *       {@code revenue - (units-1)*c} 精确吸收舍入差 —— 因为最后一行只有 1 件，(units-1)*c 又是 4 位小数，
 *       差值必然能落在 decimal(18,4) 上，销售额分毫不差。</li>
 *   <li><b>订单量</b>：领星只给 SKU 级订单数，跨 SKU 的同一订单会被重复计数（sum(SKU订单数) ≥ 店铺订单量）。
 *       这里改用 orders 接口 {@code totalList.metricList} 的店铺级真值 D 建订单号池，把当日所有行轮询分配到
 *       D 个合成单号上，distinct 恰好等于 D。行数恒 ≥ D（每 SKU 至少 max(订单数,2) 行）。</li>
 * </ol>
 *
 * <h2>合订单号的语义边界</h2>
 * {@code LX-yyyyMMdd-####} 是合成序号，<b>仅用于聚合计数</b>，不可用于订单明细追溯 ——
 * 领星不提供订单明细（订单号 / 买家 / 物流），这些信息只在 PO_Data 里，而 PO_Data 自本次切换起停用。
 *
 * <h2>幂等与窗口</h2>
 * 按「每个日期一个 import_file」替换：同步前先删窗口内旧的领星文件及其订单行，再整体重写。
 * 因此**同一窗口反复跑是安全的**（小时级调度的前提）——当日数据每跑一次就整体刷新一次。
 *
 * <p>调度与「沃尔玛在线商品同步」共用同一小时节奏（{@code walmart.ads.lingxing.cron}）；
 * 滚动窗口默认 3 天（T-2 ~ T）。服务停机造成的窗口缺口由 {@link #syncNow(String)} 的补窗逻辑
 * 依上次成功窗口自动向前扩展。领星下单时间窗口上限 90 天，2026-07-20 及更早仍由原 HISTORY 批次承载。
 */
@Service
public class LingXingOrderSyncService {

    private static final Logger log = LoggerFactory.getLogger(LingXingOrderSyncService.class);

    /** 数据来源标识，写入 sync_log.source 与 order_record.raw_payload.source */
    private static final String SOURCE = "lingxing_mcp";
    /** 领星销量统计列表v2 —— 非 platform_walmart_* 命名，容易漏查 */
    private static final String TOOL_ID = "platform_v2_page_list";
    private static final String SOURCE_ROOT = "lingxing://mcp/" + TOOL_ID;
    private static final String ORDER_NO_PREFIX = "LX-";

    /** resultType 枚举：1 销量 / 2 订单量 / 3 销售额 */
    private static final String RESULT_UNITS = "1";
    private static final String RESULT_ORDERS = "2";
    private static final String RESULT_REVENUE = "3";

    /** dataType=4 为 SKU 维度；dateUnit=4 为日粒度（广告接口没有日粒度，这个有） */
    private static final int DATA_TYPE_SKU = 4;
    private static final int DATE_UNIT_DAY = 4;
    /** 沃尔玛平台编码 */
    private static final int SEARCH_TYPE_WALMART = 3;
    private static final int PAGE_SIZE = 200;
    /** 领星下单时间窗口硬上限 */
    private static final int MAX_WINDOW_DAYS = 90;

    private final LingXingProperties properties;
    private final ImportBatchRepository batchRepository;
    private final ImportFileRepository fileRepository;
    private final OrderRecordRepository orderRepository;
    private final LingXingOrderSyncLogRepository logRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    @Autowired
    private WorkspaceLingXingConfigService workspaceConfig;

    public LingXingOrderSyncService(
            LingXingProperties properties,
            ImportBatchRepository batchRepository,
            ImportFileRepository fileRepository,
            OrderRecordRepository orderRepository,
            LingXingOrderSyncLogRepository logRepository,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
        this.orderRepository = orderRepository;
        this.logRepository = logRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 按配置的滚动窗口同步（默认近 3 天）。
     *
     * <p>若上次成功窗口已被时间甩开（停机 / 连续失败导致缺口），窗口自动向前扩展到缺口起点 ——
     * PO_Data 已停用，漏掉的日期没有第二来源可补。窗口上限仍受领星 90 天硬约束。
     */
    public SyncSummary syncNow(String triggeredBy) {
        // 窗口右端点锚定美国业务日：领星为 T+1 同步，北京 16:00 前拉"美国昨天"、
        // 16:00 后（美西当日已走完）才拉"美国今天"，避免拉到根本不存在的空尾巴。
        LocalDate end = UsBusinessDate.usToday();
        int windowDays = properties.resolvedOrderWindowDays();
        LocalDate start = end.minusDays(windowDays - 1L);
        LocalDate lastSuccessEnd = logRepository.findLatestSuccessWindowEnd();
        if (lastSuccessEnd != null && lastSuccessEnd.isBefore(start)) {
            // 重叠 1 天起拉：边界日的数据在两次同步间仍可能发生滚动变化。
            LocalDate catchUp = lastSuccessEnd.minusDays(1);
            LocalDate floor = end.minusDays(MAX_WINDOW_DAYS - 1L);
            if (catchUp.isBefore(floor)) {
                catchUp = floor;
            }
            log.info("检测到数据缺口：上次成功窗口止于 {}，本次窗口自 {} 扩展至 {}（补窗）",
                    lastSuccessEnd, catchUp, end);
            start = catchUp;
        }
        return syncWindow(triggeredBy, start, end);
    }

    /**
     * 同步指定窗口。窗口不得超过领星的 90 天硬上限（超了接口会直接拒绝）。
     */
    public SyncSummary syncWindow(String triggeredBy, LocalDate requestedStart, LocalDate requestedEnd) {
        WorkspaceLingXingConfigService.Config config = workspaceConfig.current();
        if (!config.configured()) {
            throw new IllegalStateException("领星 MCP 未配置，无法同步销量/订单数据");
        }
        LingXingClient client = new LingXingClient(config.baseUrl(), config.apiKey(), config.pageSize(),
                10, 60, objectMapper, List.of());
        LocalDate end = requestedEnd == null ? UsBusinessDate.usToday() : requestedEnd;
        LocalDate start = requestedStart == null
                ? end.minusDays(properties.resolvedOrderWindowDays() - 1L)
                : requestedStart;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("窗口起始日不能晚于结束日：" + start + " ~ " + end);
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException(
                    "窗口不得超过 %d 天（当前 %d 天）；领星下单时间窗口上限 90 天。".formatted(MAX_WINDOW_DAYS, span));
        }

        LingXingOrderSyncLog record = new LingXingOrderSyncLog(
                LocalDateTime.now(), SOURCE, triggeredBy == null ? "MANUAL" : triggeredBy, start, end);
        logRepository.save(record);
        long begin = System.currentTimeMillis();
        try {
            FetchResult fetched = fetch(client, config.storeId(), start, end);
            WriteResult written = persist(fetched);
            long duration = System.currentTimeMillis() - begin;
            record.setStatus("SUCCESS");
            record.setFetchedSkuCount(fetched.skuCount());
            record.setCoveredDayCount(written.dayCount());
            record.setInsertedCount(written.insertedCount());
            record.setDeletedCount(written.deletedCount());
            record.setStoreOrderTotal(fetched.storeOrderTotal());
            record.setFinishedAt(LocalDateTime.now());
            record.setDurationMs(duration);
            logRepository.save(record);
            log.info("领星销量/订单同步完成：窗口 {} ~ {}，SKU={}，日期={}，写入={}，清理={}，店铺订单量={}，耗时={}ms",
                    start, end, fetched.skuCount(), written.dayCount(), written.insertedCount(),
                    written.deletedCount(), fetched.storeOrderTotal(), duration);
            return new SyncSummary(start, end, fetched.skuCount(), written.dayCount(),
                    written.insertedCount(), written.deletedCount(), fetched.storeOrderTotal(),
                    "SUCCESS", null, duration, LocalDateTime.now());
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - begin;
            String message = rootMessage(exception);
            record.setStatus("FAILED");
            record.setErrorMessage(truncate(message));
            record.setFinishedAt(LocalDateTime.now());
            record.setDurationMs(duration);
            logRepository.save(record);
            log.error("领星销量/订单同步失败：{}", message);
            return new SyncSummary(start, end, 0, 0, 0, 0, BigDecimal.ZERO,
                    "FAILED", truncate(message), duration, LocalDateTime.now());
        }
    }

    // ---------------------------------------------------------------- 拉取

    /**
     * 三次调用拉齐三个指标（销量 / 订单量 / 销售额），合并为「日期 → SKU → 指标」结构。
     *
     * <p>每次调用同时带回 {@code totalList.metricList}（店铺级日粒度合计，与分页无关），
     * 其中订单量那次的结果即「店铺级订单量真值」，用于纠正跨 SKU 订单重复计数。
     */
    private FetchResult fetch(LingXingClient client, String storeId, LocalDate start, LocalDate end) {
        Map<LocalDate, Map<String, DaySku>> byDay = new TreeMap<>();
        Map<LocalDate, BigDecimal> storeUnits = new TreeMap<>();
        Map<LocalDate, BigDecimal> storeOrders = new TreeMap<>();
        Map<LocalDate, BigDecimal> storeRevenue = new TreeMap<>();
        pull(client, storeId, byDay, storeUnits, RESULT_UNITS, start, end);
        pull(client, storeId, byDay, storeOrders, RESULT_ORDERS, start, end);
        pull(client, storeId, byDay, storeRevenue, RESULT_REVENUE, start, end);
        log.debug("领星订单拉取完成：日期 {} 个，SKU {} 个，店铺订单量合计 {}",
                byDay.size(), countDistinctSku(byDay), sum(storeOrders));
        return new FetchResult(byDay, storeUnits, storeOrders, storeRevenue);
    }

    private void pull(
            LingXingClient client,
            String storeId,
            Map<LocalDate, Map<String, DaySku>> byDay,
            Map<LocalDate, BigDecimal> storeDaily,
            String resultType,
            LocalDate start,
            LocalDate end
    ) {
        int pageNum = 1;
        int total = Integer.MAX_VALUE;
        int seen = 0;
        while (seen < total) {
            JsonNode data = client.callTool(TOOL_ID, buildParams(storeId, resultType, start, end, pageNum));
            total = data.path("count").asInt();
            // 店铺级日合计独立于分页（每页返回同一份全量值），覆盖写入即可，不能累加。
            Iterator<Map.Entry<String, JsonNode>> metrics = data.path("totalList").path("metricList").fields();
            while (metrics.hasNext()) {
                Map.Entry<String, JsonNode> metric = metrics.next();
                LocalDate date = ImportValues.date(metric.getKey());
                if (date != null) {
                    storeDaily.put(date, ImportValues.decimal(metric.getValue().asText()));
                }
            }
            int pageRows = 0;
            for (JsonNode row : data.path("statisticsList")) {
                String sku = firstText(row, "sku");
                if (sku == null) {
                    continue;
                }
                String productName = firstText(row, "productName");
                Iterator<Map.Entry<String, JsonNode>> collect = row.path("dateCollect").fields();
                while (collect.hasNext()) {
                    Map.Entry<String, JsonNode> entry = collect.next();
                    LocalDate date = ImportValues.date(entry.getKey());
                    if (date == null) {
                        continue;
                    }
                    BigDecimal value = ImportValues.decimal(entry.getValue().asText());
                    DaySku target = byDay
                            .computeIfAbsent(date, key -> new TreeMap<>())
                            .computeIfAbsent(sku, key -> new DaySku(sku));
                    if (productName != null) {
                        target.productName = productName;
                    }
                    switch (resultType) {
                        case RESULT_UNITS -> target.units = target.units.add(value);
                        case RESULT_ORDERS -> target.orders = target.orders.add(value);
                        case RESULT_REVENUE -> target.revenue = target.revenue.add(value);
                        default -> throw new IllegalStateException("未知 resultType：" + resultType);
                    }
                }
                pageRows++;
            }
            seen += pageRows;
            if (pageRows == 0) {
                // 防御：分页拿不到新行时立即收手，避免 total 与 pageRows 不同步导致死循环。
                break;
            }
            pageNum++;
        }
    }

    private Map<String, Object> buildParams(String storeId, String resultType, LocalDate start, LocalDate end, int pageNum) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sids", List.of(storeId));
        params.put("searchType", SEARCH_TYPE_WALMART);
        params.put("dataType", String.valueOf(DATA_TYPE_SKU));
        params.put("start", start.toString());
        params.put("end", end.toString());
        params.put("dateUnit", String.valueOf(DATE_UNIT_DAY));
        params.put("resultType", resultType);
        params.put("currencyCode", "");
        params.put("pageNum", pageNum);
        params.put("pageSize", PAGE_SIZE);
        return params;
    }

    // ---------------------------------------------------------------- 规划

    /**
     * 把某一天的 SKU 聚合值规划成待写入的行。
     *
     * <p>顺序很重要：先按 SKU 拆行，再用店铺级订单量建号池分配单号。
     */
    private List<PlannedRow> planDay(LocalDate date, Map<String, DaySku> skus, BigDecimal storeOrderCount) {
        List<PlannedRow> rows = new ArrayList<>();
        for (DaySku sku : skus.values()) {
            rows.addAll(split(sku));
        }
        if (rows.isEmpty()) {
            return rows;
        }
        int pool = storeOrderCount == null
                ? 0
                : storeOrderCount.setScale(0, RoundingMode.HALF_UP).intValue();
        if (pool <= 0) {
            // 店铺级真值缺失时退化为「行数即订单数」，会在摘要里体现为订单量偏高。
            pool = rows.size();
            log.warn("{} 未取到店铺级订单量，退化为按行数计订单数（{} 行）", date, rows.size());
        }
        // 行数恒 ≥ 店铺订单量（每 SKU 至少 max(SKU订单数, 2) 行），这里仍做一次防御性收敛。
        pool = Math.min(pool, rows.size());
        String prefix = ORDER_NO_PREFIX + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).orderNumber = prefix + String.format(Locale.ROOT, "%04d", (i % pool) + 1);
        }
        return rows;
    }

    /**
     * 单个 (日期, SKU) 的拆行。
     *
     * <p>行数 {@code rows = min(units, max(orders, 2))}，保证最后一行恰好 1 件 —— 这是销售额能精确的关键：
     * 1 件的行可以让单价直接等于「剩余金额」，而不用再做一次除法舍入。
     */
    private List<PlannedRow> split(DaySku sku) {
        int units = sku.units.setScale(0, RoundingMode.HALF_UP).intValue();
        if (units <= 0) {
            return List.of();
        }
        int orders = sku.orders.setScale(0, RoundingMode.HALF_UP).intValue();
        if (orders <= 0) {
            orders = 1;
        }
        int rows = Math.min(units, Math.max(orders, 2));
        BigDecimal unitCost = sku.revenue.divide(BigDecimal.valueOf(units), 4, RoundingMode.HALF_UP);
        List<PlannedRow> out = new ArrayList<>(rows);
        int headUnits = units - 1;
        if (rows > 1) {
            int base = headUnits / (rows - 1);
            int extra = headUnits % (rows - 1);
            for (int i = 0; i < rows - 1; i++) {
                int quantity = base + (i < extra ? 1 : 0);
                out.add(new PlannedRow(sku, BigDecimal.valueOf(quantity), unitCost));
            }
        }
        BigDecimal lastCost = sku.revenue.subtract(unitCost.multiply(BigDecimal.valueOf(headUnits)));
        out.add(new PlannedRow(sku, BigDecimal.ONE, lastCost));
        return out;
    }

    // ---------------------------------------------------------------- 落库

    private WriteResult persist(FetchResult fetched) {
        return transactionTemplate.execute(status -> {
            ImportBatch batch = batchRepository.save(new ImportBatch(SOURCE_ROOT));
            int inserted = 0;
            int deleted = 0;
            int days = 0;
            for (Map.Entry<LocalDate, Map<String, DaySku>> entry : fetched.byDay().entrySet()) {
                LocalDate date = entry.getKey();
                List<PlannedRow> rows = planDay(date, entry.getValue(), fetched.storeOrders().get(date));
                if (rows.isEmpty()) {
                    continue;
                }
                deleted += clearStale(date);
                String content = contentOf(date, rows);
                ImportFile file = new ImportFile(
                        batch,
                        ImportFileType.LINGXING_ORDER,
                        null,
                        "lingxing-order-%s.json".formatted(date),
                        "%s?date=%s".formatted(SOURCE_ROOT, date),
                        sha256(content),
                        content.getBytes(StandardCharsets.UTF_8).length,
                        LocalDateTime.now(),
                        date,
                        LocalDateTime.now()
                );
                file.imported(null, rows.size(), rows.size());
                fileRepository.saveAndFlush(file);

                List<OrderRecord> records = new ArrayList<>(rows.size());
                for (PlannedRow row : rows) {
                    records.add(toRecord(file, date, row));
                }
                orderRepository.saveAll(records);
                inserted += records.size();
                days++;
            }
            batch.complete(fetched.byDay().size(), days, 0, 0,
                    "领星销量/订单同步 %d 天，写入 %d 行".formatted(days, inserted));
            batchRepository.save(batch);
            return new WriteResult(days, inserted, deleted);
        });
    }

    /**
     * 删除某日期下旧的领星导入文件及其订单行（先删明细，再删文件 —— 外键约束要求此顺序）。
     *
     * @return 清理掉的订单行数
     */
    private int clearStale(LocalDate date) {
        List<Long> staleFileIds = fileRepository.findIdsByFileTypeAndReportDateBetween(
                ImportFileType.LINGXING_ORDER, date, date);
        if (staleFileIds.isEmpty()) {
            return 0;
        }
        int removed = orderRepository.deleteByImportFileIdIn(staleFileIds);
        // Execute the delete immediately. Deferring entity deletes until the later insert flush lets
        // Hibernate order the INSERT before DELETE and collide with (sha256, file_type, report_date).
        fileRepository.deleteAllByIdInBatch(staleFileIds);
        log.debug("清理 {} 旧领星订单文件 {} 个、明细 {} 行", date, staleFileIds.size(), removed);
        return removed;
    }

    private OrderRecord toRecord(ImportFile file, LocalDate date, PlannedRow row) {
        return new OrderRecord(
                file,
                date,
                date,
                null,
                row.orderNumber,
                null,
                row.sku.sku,
                ImportValues.parentSku(row.sku.sku),
                null,
                null,
                row.quantity,
                row.itemCost,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                row.sku.productName,
                null,
                null,
                rawPayload(date, row)
        );
    }

    /**
     * 原始报文。领星无订单明细，这里保留该行的来源聚合值，便于按行回溯核对三等式。
     * 不含任何买家隐私字段（领星不返回这些）。
     */
    private String rawPayload(LocalDate date, PlannedRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", SOURCE);
        payload.put("toolId", TOOL_ID);
        payload.put("date", date.toString());
        payload.put("sku", row.sku.sku);
        payload.put("productName", row.sku.productName);
        payload.put("dayUnits", row.sku.units);
        payload.put("dayOrders", row.sku.orders);
        payload.put("dayRevenue", row.sku.revenue);
        payload.put("orderNumberSynthetic", true);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return "{\"source\":\"%s\"}".formatted(SOURCE);
        }
    }

    private String contentOf(LocalDate date, List<PlannedRow> rows) {
        StringBuilder builder = new StringBuilder(rows.size() * 64);
        builder.append(date).append('|').append(rows.size());
        for (PlannedRow row : rows) {
            builder.append('|').append(row.sku.sku)
                    .append(':').append(row.orderNumber)
                    .append(':').append(row.quantity.toPlainString())
                    .append(':').append(row.itemCost.toPlainString());
        }
        return builder.toString();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("摘要计算失败：" + exception.getMessage(), exception);
        }
    }

    // ---------------------------------------------------------------- 辅助

    private static String firstText(JsonNode row, String field) {
        JsonNode node = row.path(field);
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = ImportValues.blankToNull(item.asText());
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        return ImportValues.blankToNull(node.asText());
    }

    private static int countDistinctSku(Map<LocalDate, Map<String, DaySku>> byDay) {
        Set<String> skus = new HashSet<>();
        byDay.values().forEach(day -> skus.addAll(day.keySet()));
        return skus.size();
    }

    private static BigDecimal sum(Map<LocalDate, BigDecimal> values) {
        return values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    // ---------------------------------------------------------------- 内部类型

    /** 单个 SKU 单日的三个聚合指标（领星只给到这一层，没有订单明细）。 */
    private static final class DaySku {
        private final String sku;
        private String productName;
        private BigDecimal units = BigDecimal.ZERO;
        private BigDecimal orders = BigDecimal.ZERO;
        private BigDecimal revenue = BigDecimal.ZERO;

        private DaySku(String sku) {
            this.sku = sku;
        }
    }

    /** 待写入的一行：quantity/itemCost 已定，orderNumber 在当日号池分配后填入。 */
    private static final class PlannedRow {
        private final DaySku sku;
        private final BigDecimal quantity;
        private final BigDecimal itemCost;
        private String orderNumber;

        private PlannedRow(DaySku sku, BigDecimal quantity, BigDecimal itemCost) {
            this.sku = sku;
            this.quantity = quantity;
            this.itemCost = itemCost;
        }
    }

    private record FetchResult(
            Map<LocalDate, Map<String, DaySku>> byDay,
            Map<LocalDate, BigDecimal> storeUnits,
            Map<LocalDate, BigDecimal> storeOrders,
            Map<LocalDate, BigDecimal> storeRevenue
    ) {

        int skuCount() {
            return countDistinctSku(byDay);
        }

        BigDecimal storeOrderTotal() {
            return sum(storeOrders);
        }
    }

    private record WriteResult(int dayCount, int insertedCount, int deletedCount) {
    }

    public record SyncSummary(
            LocalDate windowStart,
            LocalDate windowEnd,
            int fetchedSkuCount,
            int coveredDayCount,
            int insertedCount,
            int deletedCount,
            BigDecimal storeOrderTotal,
            String status,
            String errorMessage,
            long durationMs,
            LocalDateTime finishedAt
    ) {
    }

    public Map<String, Object> toView(LingXingOrderSyncLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.getId());
        view.put("startedAt", log.getStartedAt());
        view.put("finishedAt", log.getFinishedAt());
        view.put("status", log.getStatus());
        view.put("source", log.getSource());
        view.put("triggeredBy", log.getTriggeredBy());
        view.put("windowStart", log.getWindowStart());
        view.put("windowEnd", log.getWindowEnd());
        view.put("fetchedSkuCount", log.getFetchedSkuCount());
        view.put("coveredDayCount", log.getCoveredDayCount());
        view.put("insertedCount", log.getInsertedCount());
        view.put("deletedCount", log.getDeletedCount());
        view.put("storeOrderTotal", log.getStoreOrderTotal());
        view.put("durationMs", log.getDurationMs());
        view.put("errorMessage", log.getErrorMessage());
        return view;
    }
}
