package com.ai.walmartads.operations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class OperationRecordService {

    private static final Set<String> TARGET_TYPES = Set.of("PARENT_SKU", "SKU");
    private static final Set<String> ACTION_TYPES = Set.of(
            "NOTE",
            "LISTING_TITLE",
            "LISTING_IMAGES",
            "LISTING_CONTENT",
            "PRICE",
            "AD_BID",
            "AD_BUDGET",
            "AD_KEYWORDS",
            "AD_STATUS",
            "AD_OPTIMIZE",
            "PROMOTION",
            "INVENTORY",
            "OTHER"
    );
    private static final Set<String> SOURCE_TYPES = Set.of("MANUAL", "SYSTEM", "AI");
    private static final Set<String> STATUSES = Set.of("PLANNED", "EXECUTED", "CANCELLED");
    private static final Set<String> TASK_STATUSES = Set.of("ACTIVE", "CLOSED");
    private static final int MAX_TARGET_SKUS = 500;

    /** 款号 / SKU 候选池上限（实测在售 28 款 + 786 SKU，留足余量）。 */
    private static final int MAX_TARGET_OPTIONS = 2000;

    /**
     * 款号级候选（款号 + 该款任一商品名）。
     *
     * <p>{@code :queryLike} 缺省传 {@code '%'}：此时 `= '%'` 恒真、短路后续 like，等价「不过滤」。
     */
    private static final String PARENT_SKU_TARGET_SQL = """
            select 'PARENT_SKU' as target_type,
                   p.parent_sku as target_key,
                   p.parent_sku as parent_sku,
                   max(coalesce(nullif(p.product_name, ''), p.parent_sku)) as target_name
              from wm_ads_product_record p
             where p.current_version = true
               and (:queryLike = '%'
                    or lower(p.parent_sku) like :queryLike
                    or lower(coalesce(p.product_name, '')) like :queryLike)
             group by p.parent_sku
            """;

    /** SKU 级候选（SKU + 该 SKU 商品名），供按 SKU 定位款号。 */
    private static final String SKU_TARGET_SQL = """
            select 'SKU' as target_type,
                   p.sku as target_key,
                   p.parent_sku as parent_sku,
                   max(coalesce(nullif(p.product_name, ''), p.sku)) as target_name
              from wm_ads_product_record p
             where p.current_version = true
               and (:queryLike = '%'
                    or lower(p.sku) like :queryLike
                    or lower(coalesce(p.product_name, '')) like :queryLike)
             group by p.sku, p.parent_sku
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> SKU_LIST_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final OperationWindowCalculator windowCalculator;

    public OperationRecordService(
            NamedParameterJdbcTemplate jdbc,
            OperationWindowCalculator windowCalculator
    ) {
        this.jdbc = jdbc;
        this.windowCalculator = windowCalculator;
    }

    @Transactional
    public OperationRecord create(CreateCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("运营记录不能为空。");
        }
        List<String> targetKeys = normalizeSkus(command.targetKeys());
        if (targetKeys.isEmpty() && blank(command.targetType()) && blank(command.targetKey())) {
            throw new IllegalArgumentException("请至少选择 1 个作用 SKU（全选即表示整款）。");
        }
        String actionType = enumValue(command.actionType(), ACTION_TYPES, "操作类型");
        String sourceType = optionalEnum(command.sourceType(), SOURCE_TYPES, "MANUAL", "执行来源");
        String status = optionalEnum(command.status(), STATUSES, "EXECUTED", "执行状态");
        String title = required(command.title(), "操作摘要", 240);
        String beforeValue = optional(command.beforeValue(), 4000, "调整前内容");
        String afterValue = optional(command.afterValue(), 4000, "调整后内容");
        String note = optional(command.note(), 8000, "备注");
        String externalReference = optional(command.externalReference(), 240, "外部关联号");
        LocalDateTime occurredAt = command.occurredAt() == null ? LocalDateTime.now() : command.occurredAt();
        if (occurredAt.isAfter(LocalDateTime.now().plusDays(1))) {
            throw new IllegalArgumentException("操作时间不能晚于明天。");
        }

        String targetType;
        String targetKey;
        String parentSku;
        String targetName;
        ReviewTaskRef task;
        if (!targetKeys.isEmpty()) {
            // 新口径：作用范围 = 该款下的 SKU 集合；全选（数量达到全款在售数）即等价整款。
            task = requireActiveTask(command.reviewTaskId(), null);
            Set<String> available = skusOfParent(task.parentSku());
            if (available.isEmpty()) {
                throw new IllegalArgumentException("款号 " + task.parentSku() + " 没有在售 SKU，无法记录运营动作。");
            }
            // 大小写不敏感匹配，但落库统一用产品库中的原始 SKU 写法
            Map<String, String> canonical = new HashMap<>();
            available.forEach(sku -> canonical.putIfAbsent(sku.toUpperCase(Locale.ROOT), sku));
            List<String> unknown = targetKeys.stream()
                    .filter(sku -> !canonical.containsKey(sku))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("以下 SKU 不属于款号 " + task.parentSku() + "："
                        + String.join("、", unknown));
            }
            targetKeys = targetKeys.stream().map(canonical::get).toList();
            boolean wholeStyle = targetKeys.size() >= available.size();
            targetType = wholeStyle ? "PARENT_SKU" : "SKU";
            targetKey = wholeStyle ? task.parentSku() : targetKeys.getFirst();
            parentSku = task.parentSku();
            targetName = wholeStyle ? nameOfParent(task.parentSku()) : nameOfSku(targetKeys.getFirst());
        } else {
            // 兼容口径：显式给出作用范围与目标（旧接口 / 脚本）。
            String legacyType = enumValue(command.targetType(), TARGET_TYPES, "作用范围");
            String legacyKey = required(command.targetKey(), "SKU 或款号", 180);
            TargetIdentity identity = resolveTarget(legacyType, legacyKey);
            task = requireActiveTask(command.reviewTaskId(), identity.parentSku());
            targetType = legacyType;
            targetKey = legacyKey;
            parentSku = identity.parentSku() == null ? task.parentSku() : identity.parentSku();
            targetName = identity.targetName();
        }
        String targetKeysJson = targetKeys.isEmpty() ? null : writeSkus(targetKeys);
        LocalDateTime now = LocalDateTime.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", targetType)
                .addValue("targetKey", targetKey)
                .addValue("targetKeys", targetKeysJson)
                .addValue("parentSku", parentSku)
                .addValue("reviewTaskId", task.id())
                .addValue("targetName", targetName)
                .addValue("actionType", actionType)
                .addValue("sourceType", sourceType)
                .addValue("status", status)
                .addValue("occurredAt", occurredAt)
                .addValue("title", title)
                .addValue("beforeValue", beforeValue)
                .addValue("afterValue", afterValue)
                .addValue("note", note)
                .addValue("externalReference", externalReference)
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into wm_ads_operation_record (
                    target_type, target_key, target_keys, parent_sku, review_task_id, target_name,
                    action_type, source_type, status, occurred_at,
                    title, before_value, after_value, note, external_reference,
                    created_at, updated_at
                ) values (
                    :targetType, :targetKey, cast(:targetKeys as JSON), :parentSku, :reviewTaskId, :targetName,
                    :actionType, :sourceType, :status, :occurredAt,
                    :title, :beforeValue, :afterValue, :note, :externalReference,
                    :createdAt, :updatedAt
                )
                """, params, keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("运营记录保存成功，但未返回记录编号。");
        }
        return findRecord(id.longValue());
    }

    private ReviewTaskRef requireActiveTask(Long taskId, String parentSku) {
        if (taskId == null) {
            throw new IllegalArgumentException("运营记录必须归属一个复盘任务。");
        }
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    select id, parent_sku, status
                      from wm_ads_review_task
                     where id = :id
                    """, new MapSqlParameterSource("id", taskId));
        } catch (EmptyResultDataAccessException ignored) {
            throw new IllegalArgumentException("找不到复盘任务 #" + taskId + "。");
        }
        String status = text(row, "status");
        if (!"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("该复盘任务已结束，不能再追加运营记录。");
        }
        String taskSku = text(row, "parent_sku");
        if (parentSku != null && taskSku != null && !parentSku.equals(taskSku)) {
            throw new IllegalArgumentException(
                    "运营记录的作用款号（" + parentSku + "）与该复盘任务（" + taskSku + "）不一致。");
        }
        return new ReviewTaskRef(longNumber(row, "id"), taskSku);
    }

    @Transactional(readOnly = true)
    public OperationPayload list(
            int days,
            String targetType,
            String targetKey,
            String query,
            int limit
    ) {
        return list(days, null, null, targetType, targetKey, query, limit);
    }

    @Transactional(readOnly = true)
    public OperationPayload list(
            int days,
            LocalDate startDate,
            LocalDate endDate,
            String targetType,
            String targetKey,
            String query,
            int limit
    ) {
        if ((startDate == null) != (endDate == null)) {
            throw new IllegalArgumentException("自定义时间范围必须同时提供开始日期和结束日期。");
        }
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期。");
        }
        int safeDays = startDate == null
                ? Math.max(1, Math.min(days, 90))
                : Math.max(1, Math.min(
                        Math.toIntExact(ChronoUnit.DAYS.between(startDate, endDate) + 1),
                        90
                ));
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String safeTargetType = blank(targetType) ? null : enumValue(targetType, TARGET_TYPES, "作用范围");
        String safeTargetKey = blank(targetKey) ? null : targetKey.trim();
        String safeQuery = blank(query) ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", safeTargetType)
                .addValue("targetKey", safeTargetKey)
                .addValue("query", safeQuery)
                .addValue("rangeStart", startDate == null ? null : startDate.atStartOfDay())
                .addValue("rangeEnd", endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .addValue("limit", safeLimit);
        List<OperationRecord> records = jdbc.queryForList("""
                select id, target_type, target_key, target_keys, parent_sku, review_task_id, target_name,
                       action_type, source_type, status, occurred_at,
                       title, before_value, after_value, note, external_reference,
                       created_at, updated_at
                  from wm_ads_operation_record
                 where (:targetType is null or target_type = :targetType)
                   and (:targetKey is null
                        or target_key = :targetKey
                        or json_contains(target_keys, json_quote(:targetKey)))
                   and (:rangeStart is null or occurred_at >= :rangeStart)
                   and (:rangeEnd is null or occurred_at < :rangeEnd)
                   and (:query is null
                        or lower(target_key) like :query
                        or lower(cast(coalesce(target_keys, '[]') as char)) like :query
                        or lower(coalesce(parent_sku, '')) like :query
                        or lower(coalesce(target_name, '')) like :query
                        or lower(title) like :query
                        or lower(coalesce(note, '')) like :query)
                 order by occurred_at desc, id desc
                 limit :limit
                """, params).stream().map(this::mapRecord).toList();

        LocalDate latestDataDate = latestDataDate();
        List<OperationWithImpact> items = records.stream()
                .map(record -> new OperationWithImpact(record, impact(record, latestDataDate, safeDays)))
                .toList();
        long executed = records.stream().filter(record -> "EXECUTED".equals(record.status())).count();
        long system = records.stream()
                .filter(record -> "EXECUTED".equals(record.status()))
                .filter(record -> !"MANUAL".equals(record.sourceType()))
                .count();
        long ready = items.stream().filter(item -> "READY".equals(item.impact().state())).count();
        return new OperationPayload(
                new OperationMeta(safeDays, startDate, endDate, latestDataDate, records.size(), executed, system, ready),
                items
        );
    }

    /**
     * 运营记录的款号 / SKU 候选池。
     *
     * <p>数据源统一为产品库 {@code wm_ads_product_record}（= 领星在线商品同步来的在售清单），
     * 与 {@link #skusOfParent}、{@link #nameOfParent} 同源；已淘汰款（如 PK1001 / PNK366067~073）
     * 不再出现在下拉里。
     *
     * <p>{@code targetType} 让类型过滤与分页下推到 SQL：此前是「两类全量查 → 内存按 limit 截断」，
     * 而款号排序在前，SKU 级永远只能拿到尾部残量（前端按 SKU 反查款号会查不到）。
     *
     * @param targetType {@code PARENT_SKU} / {@code SKU}；留空 = 两类都要
     */
    @Transactional(readOnly = true)
    public List<TargetOption> targets(String targetType, String query, int limit) {
        String safeType = blank(targetType) ? null : enumValue(targetType, TARGET_TYPES, "作用范围");
        String safeQuery = blank(query) ? "" : query.trim().toLowerCase(Locale.ROOT);
        int safeLimit = Math.max(1, Math.min(limit, MAX_TARGET_OPTIONS));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("queryLike", "%" + safeQuery + "%");

        List<String> parts = new ArrayList<>();
        if (!"SKU".equals(safeType)) {
            parts.add(PARENT_SKU_TARGET_SQL);
        }
        if (!"PARENT_SKU".equals(safeType)) {
            parts.add(SKU_TARGET_SQL);
        }
        // safeLimit 已被夹到 [1, 2000] 的整型，拼接无注入风险。
        String sql = parts.size() == 1
                ? parts.getFirst() + " order by target_key limit " + safeLimit
                : "select target_type, target_key, parent_sku, target_name\n  from ("
                        + String.join(" union all ", parts)
                        + ") targets\n order by target_type, target_key limit " + safeLimit;

        return jdbc.queryForList(sql, params).stream()
                .map(row -> new TargetOption(
                        text(row, "target_type"),
                        text(row, "target_key"),
                        text(row, "parent_sku"),
                        text(row, "target_name")
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReminderPayload listReminders(String parentSku, String scope) {
        String safeParentSku = blank(parentSku) ? null : parentSku.trim();
        String safeScope = blank(scope) ? "due" : scope.trim().toUpperCase(Locale.ROOT);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("parentSku", safeParentSku);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.review_task_id, r.due_date, r.label, r.status,
                       r.resolved_at, r.created_at, r.updated_at,
                       t.parent_sku, t.title as task_title, t.status as task_status
                  from wm_ads_operation_reminder r
                  join wm_ads_review_task t on t.id = r.review_task_id
                 where (:parentSku is null or t.parent_sku = :parentSku)
                 order by r.due_date asc, r.id asc
                """, params);
        LocalDate today = LocalDate.now();
        List<ReminderWithTask> items = rows.stream()
                .map(row -> {
                    LocalDate dueDate = localDate(row.get("due_date"));
                    LocalDateTime resolvedAt = localDateTime(row.get("resolved_at"));
                    String computed = computeStatus(resolvedAt, dueDate, today);
                    return new ReminderWithTask(mapReminder(row, computed), mapReminderTask(row), computed);
                })
                .toList();
        List<ReminderWithTask> filtered = "ALL".equals(safeScope)
                ? items
                : items.stream()
                .filter(it -> !"PENDING".equals(it.computedStatus()) && !"DONE".equals(it.computedStatus()))
                .toList();
        long dueCount = items.stream()
                .filter(it -> !"PENDING".equals(it.computedStatus()) && !"DONE".equals(it.computedStatus()))
                .count();
        return new ReminderPayload(dueCount, filtered);
    }

    @Transactional
    public void resolveReminder(long id) {
        int updated = jdbc.update("""
                update wm_ads_operation_reminder
                   set status = 'DONE', resolved_at = :now, updated_at = :now
                 where id = :id and status <> 'DONE'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", LocalDateTime.now()));
        if (updated == 0) {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from wm_ads_operation_reminder where id = :id",
                    new MapSqlParameterSource("id", id), Integer.class);
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("找不到提醒 #" + id);
            }
        }
    }

    // ===== 复盘任务：款 › 任务 › 记录 =====

    @Transactional
    public ReviewTask createTask(CreateTaskCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("复盘任务不能为空。");
        }
        String parentSku = required(command.parentSku(), "款号", 80).toUpperCase(Locale.ROOT);
        String title = required(command.title(), "复盘任务标题", 240);
        String goal = optional(command.goal(), 4000, "复盘目标");
        LocalDateTime startedAt = command.startedAt() == null ? LocalDateTime.now() : command.startedAt();
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into wm_ads_review_task (
                    parent_sku, title, goal, status, started_at, created_at, updated_at
                ) values (
                    :parentSku, :title, :goal, 'ACTIVE', :startedAt, :now, :now
                )
                """, new MapSqlParameterSource()
                .addValue("parentSku", parentSku)
                .addValue("title", title)
                .addValue("goal", goal)
                .addValue("startedAt", startedAt)
                .addValue("now", now), keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("复盘任务保存成功，但未返回任务编号。");
        }
        long taskId = id.longValue();
        List<Checkpoint> checkpoints = command.checkpoints() == null ? List.of() : command.checkpoints();
        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint == null || checkpoint.afterDays() == null) {
                continue;
            }
            int afterDays = Math.max(1, Math.min(checkpoint.afterDays(), 365));
            LocalDate dueDate = startedAt.toLocalDate().plusDays(afterDays);
            String label = optional(checkpoint.label(), 120, "检查点标签");
            if (label == null) {
                label = afterDays + " 天复盘";
            }
            jdbc.update("""
                    insert into wm_ads_operation_reminder (
                        review_task_id, due_date, label, status, created_at, updated_at
                    ) values (
                        :taskId, :dueDate, :label, 'PENDING', :now, :now
                    )
                    """, new MapSqlParameterSource()
                    .addValue("taskId", taskId)
                    .addValue("dueDate", dueDate)
                    .addValue("label", label)
                    .addValue("now", now));
        }
        return findTask(taskId);
    }

    @Transactional
    public ReviewTask closeTask(long id, String conclusion) {
        String safeConclusion = required(conclusion, "复盘结论", 8000);
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update("""
                update wm_ads_review_task
                   set status = 'CLOSED', closed_at = :now, conclusion = :conclusion, updated_at = :now
                 where id = :id and status = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", now)
                .addValue("conclusion", safeConclusion));
        if (updated == 0) {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from wm_ads_review_task where id = :id",
                    new MapSqlParameterSource("id", id), Integer.class);
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("找不到复盘任务 #" + id);
            }
            throw new IllegalArgumentException("该复盘任务已经结束，无需重复关闭。");
        }
        return findTask(id);
    }

    @Transactional(readOnly = true)
    public ReviewTask findTask(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, parent_sku, title, goal, status, started_at, closed_at, conclusion
                  from wm_ads_review_task
                 where id = :id
                """, new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到复盘任务 #" + id);
        }
        return toReviewTask(rows.getFirst(), taskCheckpoints(id), taskRecords(id, 30, latestDataDate()));
    }

    @Transactional(readOnly = true)
    public List<TaskBrief> listTasks(String parentSku) {
        String safeParentSku = blank(parentSku) ? null : parentSku.trim().toUpperCase(Locale.ROOT);
        return jdbc.queryForList("""
                select id, parent_sku, title, status
                  from wm_ads_review_task
                 where (:parentSku is null or parent_sku = :parentSku)
                 order by (status = 'ACTIVE') desc, started_at desc, id desc
                """, new MapSqlParameterSource("parentSku", safeParentSku)).stream()
                .map(row -> new TaskBrief(
                        longNumber(row, "id"),
                        text(row, "parent_sku"),
                        text(row, "title"),
                        text(row, "status")
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public BoardPayload board(int days, String query, int limit) {
        int safeDays = Math.max(1, Math.min(days, 90));
        int safeLimit = Math.max(1, Math.min(limit, 300));
        String safeQuery = blank(query) ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        LocalDate latestDataDate = latestDataDate();

        List<Map<String, Object>> taskRows = jdbc.queryForList("""
                select t.id, t.parent_sku, t.title, t.goal, t.status,
                       t.started_at, t.closed_at, t.conclusion
                  from wm_ads_review_task t
                 where (:query is null
                        or lower(t.parent_sku) like :query
                        or lower(t.title) like :query
                        or lower(coalesce(t.goal, '')) like :query
                        or lower(coalesce(t.conclusion, '')) like :query
                        or exists (
                              select 1
                                from wm_ads_operation_record r
                               where r.review_task_id = t.id
                                 and (lower(r.target_key) like :query
                                      or lower(cast(coalesce(r.target_keys, '[]') as char)) like :query
                                      or lower(coalesce(r.parent_sku, '')) like :query
                                      or lower(r.title) like :query
                                      or lower(coalesce(r.note, '')) like :query)))
                 order by t.started_at desc, t.id desc
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("query", safeQuery)
                .addValue("limit", safeLimit));
        if (taskRows.isEmpty()) {
            return new BoardPayload(new BoardMeta(safeDays, latestDataDate, 0, 0, 0, 0), List.of());
        }

        List<Long> taskIds = taskRows.stream().map(row -> longNumber(row, "id")).toList();
        Map<Long, List<OperationRecord>> recordsByTask = new LinkedHashMap<>();
        taskIds.forEach(it -> recordsByTask.put(it, new ArrayList<>()));
        jdbc.queryForList("""
                select id, target_type, target_key, target_keys, parent_sku, review_task_id, target_name,
                       action_type, source_type, status, occurred_at,
                       title, before_value, after_value, note, external_reference,
                       created_at, updated_at
                  from wm_ads_operation_record
                 where review_task_id in (:taskIds)
                 order by occurred_at desc, id desc
                """, new MapSqlParameterSource("taskIds", taskIds))
                .forEach(row -> recordsByTask
                        .computeIfAbsent(longNumber(row, "review_task_id"), key -> new ArrayList<>())
                        .add(mapRecord(row)));

        LocalDate today = LocalDate.now();
        Map<Long, List<TaskCheckpoint>> checkpointsByTask = new LinkedHashMap<>();
        taskIds.forEach(it -> checkpointsByTask.put(it, new ArrayList<>()));
        jdbc.queryForList("""
                select id, review_task_id, due_date, label, status, resolved_at
                  from wm_ads_operation_reminder
                 where review_task_id in (:taskIds)
                 order by due_date asc, id asc
                """, new MapSqlParameterSource("taskIds", taskIds))
                .forEach(row -> checkpointsByTask
                        .computeIfAbsent(longNumber(row, "review_task_id"), key -> new ArrayList<>())
                        .add(mapReminder(row, computeStatus(
                                localDateTime(row.get("resolved_at")),
                                localDate(row.get("due_date")),
                                today))));

        Map<String, List<ReviewTask>> tasksBySku = new LinkedHashMap<>();
        Map<String, String> productNameBySku = new HashMap<>();
        long recordCount = 0;
        long executedCount = 0;
        long activeTaskCount = 0;
        long dueCheckpointCount = 0;
        for (Map<String, Object> row : taskRows) {
            long taskId = longNumber(row, "id");
            String parentSku = text(row, "parent_sku");
            List<OperationRecord> records = recordsByTask.getOrDefault(taskId, List.of());
            List<TaskCheckpoint> checkpoints = checkpointsByTask.getOrDefault(taskId, List.of());
            List<OperationWithImpact> items = records.stream()
                    .map(record -> new OperationWithImpact(record, impact(record, latestDataDate, safeDays)))
                    .toList();
            ReviewTask task = toReviewTask(row, checkpoints, items);
            tasksBySku.computeIfAbsent(parentSku, key -> new ArrayList<>()).add(task);
            recordCount += records.size();
            executedCount += records.stream().filter(it -> "EXECUTED".equals(it.status())).count();
            if ("ACTIVE".equals(task.status())) {
                activeTaskCount++;
            }
            dueCheckpointCount += checkpoints.stream()
                    .filter(it -> !"PENDING".equals(it.computedStatus()) && !"DONE".equals(it.computedStatus()))
                    .count();
            if (productNameBySku.get(parentSku) == null) {
                records.stream()
                        .map(OperationRecord::targetName)
                        .filter(it -> it != null && !it.isBlank())
                        .findFirst()
                        .ifPresent(name -> productNameBySku.put(parentSku, name));
            }
        }

        List<OperationGroup> groups = tasksBySku.entrySet().stream()
                .map(entry -> {
                    List<ReviewTask> tasks = entry.getValue().stream()
                            .sorted(Comparator
                                    .comparing((ReviewTask task) -> "ACTIVE".equals(task.status()) ? 0 : 1)
                                    .thenComparing(ReviewTask::startedAt, Comparator.reverseOrder()))
                            .toList();
                    LocalDateTime lastActivityAt = tasks.stream()
                            .flatMap(task -> task.records().stream())
                            .map(item -> item.record().occurredAt())
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElseGet(() -> tasks.stream()
                                    .map(ReviewTask::startedAt)
                                    .filter(Objects::nonNull)
                                    .max(LocalDateTime::compareTo)
                                    .orElse(null));
                    int groupRecordCount = tasks.stream().mapToInt(task -> task.records().size()).sum();
                    int activeCount = (int) tasks.stream()
                            .filter(task -> "ACTIVE".equals(task.status()))
                            .count();
                    return new OperationGroup(
                            entry.getKey(),
                            productNameBySku.get(entry.getKey()),
                            lastActivityAt,
                            groupRecordCount,
                            activeCount,
                            tasks
                    );
                })
                .sorted(Comparator.comparing(
                        OperationGroup::lastActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        return new BoardPayload(
                new BoardMeta(safeDays, latestDataDate, recordCount, executedCount, activeTaskCount, dueCheckpointCount),
                groups
        );
    }

    private List<TaskCheckpoint> taskCheckpoints(long taskId) {
        LocalDate today = LocalDate.now();
        return jdbc.queryForList("""
                select id, review_task_id, due_date, label, status, resolved_at
                  from wm_ads_operation_reminder
                 where review_task_id = :taskId
                 order by due_date asc, id asc
                """, new MapSqlParameterSource("taskId", taskId)).stream()
                .map(row -> mapReminder(row, computeStatus(
                        localDateTime(row.get("resolved_at")),
                        localDate(row.get("due_date")),
                        today)))
                .toList();
    }

    private List<OperationWithImpact> taskRecords(long taskId, int days, LocalDate latestDataDate) {
        return jdbc.queryForList("""
                select id, target_type, target_key, target_keys, parent_sku, review_task_id, target_name,
                       action_type, source_type, status, occurred_at,
                       title, before_value, after_value, note, external_reference,
                       created_at, updated_at
                  from wm_ads_operation_record
                 where review_task_id = :taskId
                 order by occurred_at desc, id desc
                """, new MapSqlParameterSource("taskId", taskId)).stream()
                .map(this::mapRecord)
                .map(record -> new OperationWithImpact(record, impact(record, latestDataDate, days)))
                .toList();
    }

    private static ReviewTask toReviewTask(
            Map<String, Object> row,
            List<TaskCheckpoint> checkpoints,
            List<OperationWithImpact> records
    ) {
        int done = (int) checkpoints.stream()
                .filter(it -> "DONE".equals(it.computedStatus()))
                .count();
        return new ReviewTask(
                longNumber(row, "id"),
                text(row, "parent_sku"),
                text(row, "title"),
                text(row, "goal"),
                text(row, "status"),
                localDateTime(row.get("started_at")),
                localDateTime(row.get("closed_at")),
                text(row, "conclusion"),
                new TaskProgress(done, checkpoints.size()),
                checkpoints,
                records
        );
    }

    private static String computeStatus(LocalDateTime resolvedAt, LocalDate dueDate, LocalDate today) {
        if (resolvedAt != null) {
            return "DONE";
        }
        if (dueDate.isAfter(today)) {
            return "PENDING";
        }
        if (dueDate.isBefore(today.minusDays(3))) {
            return "OVERDUE";
        }
        return "DUE";
    }

    private static TaskCheckpoint mapReminder(Map<String, Object> row, String computedStatus) {
        return new TaskCheckpoint(
                longNumber(row, "id"),
                longNumber(row, "review_task_id"),
                localDate(row.get("due_date")),
                text(row, "label"),
                text(row, "status"),
                localDateTime(row.get("resolved_at")),
                computedStatus
        );
    }

    private static TaskBrief mapReminderTask(Map<String, Object> row) {
        return new TaskBrief(
                longNumber(row, "review_task_id"),
                text(row, "parent_sku"),
                text(row, "task_title"),
                text(row, "task_status")
        );
    }

    private OperationImpact impact(OperationRecord record, LocalDate latestDataDate, int days) {
        if (!"EXECUTED".equals(record.status())) {
            return new OperationImpact(
                    "NOT_EXECUTED", days, 0, latestDataDate,
                    null, null, 0,
                    "只有标记为已执行的操作才计算效果。"
            );
        }
        OperationWindowCalculator.EvaluationWindow window = windowCalculator.calculate(
                record.occurredAt().toLocalDate(),
                latestDataDate,
                days
        );
        MetricSnapshot before = metrics(
                record.targetType(),
                record.targetKey(),
                window.beforeStart(),
                window.beforeEnd(),
                window.comparedDays() > 0 ? window.comparedDays() : window.requestedDays()
        );
        MetricSnapshot after = window.comparedDays() == 0
                ? null
                : metrics(
                        record.targetType(),
                        record.targetKey(),
                        window.afterStart(),
                        window.afterEnd(),
                        window.comparedDays()
                );
        int overlaps = window.comparedDays() == 0
                ? 0
                : overlappingOperations(record, window.beforeStart(), window.afterEnd());
        String message = switch (window.state()) {
            case "WAITING" -> "等待导入操作日之后的数据；当前仅展示操作前基线。";
            case "COLLECTING" -> "后续数据仍在积累，当前按前后等长天数进行阶段性比较。";
            default -> "已获得完整前后等长窗口，可用于复盘；相关变化不等于绝对因果。";
        };
        return new OperationImpact(
                window.state(),
                window.requestedDays(),
                window.comparedDays(),
                latestDataDate,
                before,
                after,
                overlaps,
                message
        );
    }

    private MetricSnapshot metrics(
            String targetType,
            String targetKey,
            LocalDate start,
            LocalDate end,
            int calendarDays
    ) {
        String orderTarget = "PARENT_SKU".equals(targetType)
                ? "coalesce(orders.parent_sku, orders.sku)"
                : "orders.sku";
        String adTarget = "PARENT_SKU".equals(targetType)
                ? "ads.parent_sku"
                : "ads.sku";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetKey", targetKey)
                .addValue("start", start)
                .addValue("end", end);
        Map<String, Object> order = jdbc.queryForMap("""
                select coalesce(sum(orders.quantity), 0) as units,
                       coalesce(sum(orders.quantity * orders.item_cost), 0) as sales
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file
                    on file.id = orders.import_file_id and file.active = true
                 where orders.report_date between :start and :end
                   and %s = :targetKey
                """.formatted(orderTarget), params);
        Map<String, Object> ad = jdbc.queryForMap("""
                select coalesce(sum(ads.impressions), 0) as impressions,
                       coalesce(sum(ads.clicks), 0) as clicks,
                       coalesce(sum(ads.ad_spend), 0) as spend,
                       coalesce(sum(ads.attributed_orders), 0) as ad_orders,
                       coalesce(sum(ads.attributed_sales), 0) as ad_sales
                  from wm_ads_lx_ad_report ads
                 where ads.report_level = 'AD_ITEM'
                   and ads.report_date between :start and :end
                   and %s = :targetKey
                """.formatted(adTarget), params);
        double units = number(order, "units");
        double sales = number(order, "sales");
        double impressions = number(ad, "impressions");
        double clicks = number(ad, "clicks");
        double spend = number(ad, "spend");
        double adOrders = number(ad, "ad_orders");
        double adSales = number(ad, "ad_sales");
        int safeCalendarDays = Math.max(calendarDays, 1);
        return new MetricSnapshot(
                start,
                end,
                safeCalendarDays,
                round(units),
                round(sales),
                round(impressions),
                round(clicks),
                round(spend),
                round(adOrders),
                round(adSales),
                round(spend == 0 ? 0 : adSales / spend),
                round(clicks == 0 ? 0 : spend / clicks),
                round(units / safeCalendarDays),
                round(sales / safeCalendarDays),
                round(spend / safeCalendarDays),
                round(adSales / safeCalendarDays)
        );
    }

    private int overlappingOperations(OperationRecord record, LocalDate start, LocalDate end) {
        Integer result = jdbc.queryForObject("""
                select count(*)
                  from wm_ads_operation_record other
                 where other.id <> :id
                   and other.status = 'EXECUTED'
                   and other.occurred_at >= :startAt
                   and other.occurred_at < :endAt
                   and (
                        (:parentSku is not null and other.parent_sku = :parentSku)
                        or (:parentSku is null
                            and other.target_type = :targetType
                            and other.target_key = :targetKey)
                   )
                """, new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("parentSku", record.parentSku())
                .addValue("targetType", record.targetType())
                .addValue("targetKey", record.targetKey())
                .addValue("startAt", start.atStartOfDay())
                .addValue("endAt", end.plusDays(1).atStartOfDay()), Integer.class);
        return result == null ? 0 : result;
    }

    private LocalDate latestDataDate() {
        return jdbc.query("""
                select max(source.latest_date) as latest_date
                  from (
                        select max(orders.report_date) as latest_date
                          from wm_ads_order_record_valid orders
                          join wm_ads_import_file file
                            on file.id = orders.import_file_id and file.active = true
                        union all
                        select max(ads.report_date) as latest_date
                          from wm_ads_lx_ad_report ads
                         where ads.report_level = 'AD_ITEM'
                       ) source
                """, new MapSqlParameterSource(), resultSet ->
                resultSet.next() && resultSet.getDate("latest_date") != null
                        ? resultSet.getDate("latest_date").toLocalDate()
                        : null
        );
    }

    // ===== 作用 SKU 集合 =====

    /**
     * 某款下在售 SKU 的候选列表（供新增运营记录时勾选）。
     */
    /**
     * 某款下在售 SKU 的候选列表（供新增运营记录时勾选）。
     *
     * <p>款号、SKU、商品名、Item ID 全部取自产品库：实测 786 个在售 SKU 的
     * {@code product_record.product_id} 与映射表 {@code item_id} **逐行一致且双侧零缺失**，
     * 故不再 join {@code wm_ads_sku_mapping}。
     */
    @Transactional(readOnly = true)
    public List<TargetSkuOption> skusOfParentOptions(String parentSku) {
        String safeParentSku = blank(parentSku) ? null : parentSku.trim().toUpperCase(Locale.ROOT);
        if (safeParentSku == null) {
            throw new IllegalArgumentException("请先填写款号。");
        }
        return jdbc.queryForList("""
                select p.sku,
                       max(p.size) as size_value,
                       max(p.color) as color_value,
                       max(coalesce(nullif(p.product_name, ''), p.sku)) as sku_name,
                       max(p.product_id) as item_id
                  from wm_ads_product_record p
                 where p.current_version = true
                   and p.parent_sku = :parentSku
                 group by p.sku
                 order by p.sku
                """, new MapSqlParameterSource("parentSku", safeParentSku)).stream()
                .map(row -> new TargetSkuOption(
                        text(row, "sku"),
                        text(row, "item_id"),
                        text(row, "size_value"),
                        text(row, "color_value"),
                        text(row, "sku_name")
                ))
                .toList();
    }

    private Set<String> skusOfParent(String parentSku) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                select sku
                  from wm_ads_product_record
                 where current_version = true
                   and parent_sku = :parentSku
                 order by sku
                """, new MapSqlParameterSource("parentSku", parentSku))
                .stream()
                .map(row -> text(row, "sku"))
                .filter(sku -> sku != null && !sku.isEmpty())
                .toList());
    }

    private String nameOfParent(String parentSku) {
        List<String> names = jdbc.queryForList("""
                select max(coalesce(nullif(product_name, ''), parent_sku)) as name_value
                  from wm_ads_product_record
                 where current_version = true
                   and parent_sku = :parentSku
                """, new MapSqlParameterSource("parentSku", parentSku))
                .stream()
                .map(row -> text(row, "name_value"))
                .filter(name -> name != null && !name.isEmpty())
                .toList();
        return names.isEmpty() ? null : names.getFirst();
    }

    private String nameOfSku(String sku) {
        List<String> names = jdbc.queryForList("""
                select max(coalesce(nullif(product_name, ''), sku)) as name_value
                  from wm_ads_product_record
                 where current_version = true
                   and sku = :sku
                """, new MapSqlParameterSource("sku", sku))
                .stream()
                .map(row -> text(row, "name_value"))
                .filter(name -> name != null && !name.isEmpty())
                .toList();
        return names.isEmpty() ? null : names.getFirst();
    }

    private static List<String> normalizeSkus(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String sku = item.trim().toUpperCase(Locale.ROOT);
            if (!sku.isEmpty()) {
                values.add(sku);
            }
        }
        if (values.size() > MAX_TARGET_SKUS) {
            throw new IllegalArgumentException("一次最多只能选择 500 个 SKU。");
        }
        return List.copyOf(values);
    }

    private static String writeSkus(List<String> skus) {
        try {
            return OBJECT_MAPPER.writeValueAsString(skus);
        } catch (JsonProcessingException ignored) {
            throw new IllegalStateException("SKU 集合序列化失败。");
        }
    }

    private static List<String> readSkus(Object raw) {
        if (raw == null) {
            return List.of();
        }
        String value = raw instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(raw);
        value = value.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return List.of();
        }
        try {
            List<String> parsed = OBJECT_MAPPER.readValue(value, SKU_LIST_TYPE);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    /**
     * 兼容口径：把显式给出的作用范围解析成 parent_sku + 展示名。
     *
     * <p>与 {@link #targets} 同源（产品库 = 在售清单）。查不到时返回空件（已淘汰款即属此列），
     * 调用方 {@code create} 会回落到所属任务的款号，不会报错。
     */
    private TargetIdentity resolveTarget(String targetType, String targetKey) {
        boolean parent = "PARENT_SKU".equals(targetType);
        String where = parent ? "p.parent_sku = :targetKey" : "p.sku = :targetKey";
        String nameFallback = parent ? "p.parent_sku" : "p.sku";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select max(p.parent_sku) as parent_sku,
                       max(coalesce(nullif(p.product_name, ''), %s)) as target_name
                  from wm_ads_product_record p
                 where p.current_version = true
                   and %s
                """.formatted(nameFallback, where), new MapSqlParameterSource("targetKey", targetKey));
        if (rows.isEmpty() || text(rows.getFirst(), "parent_sku") == null) {
            return new TargetIdentity(parent ? targetKey : null, null);
        }
        return new TargetIdentity(
                text(rows.getFirst(), "parent_sku"),
                text(rows.getFirst(), "target_name")
        );
    }

    private OperationRecord findRecord(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, target_type, target_key, target_keys, parent_sku, review_task_id, target_name,
                       action_type, source_type, status, occurred_at,
                       title, before_value, after_value, note, external_reference,
                       created_at, updated_at
                  from wm_ads_operation_record
                 where id = :id
                """, new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到运营记录 #" + id);
        }
        return mapRecord(rows.getFirst());
    }

    private OperationRecord mapRecord(Map<String, Object> row) {
        return new OperationRecord(
                longNumber(row, "id"),
                text(row, "target_type"),
                text(row, "target_key"),
                readSkus(row.get("target_keys")),
                text(row, "parent_sku"),
                text(row, "target_name"),
                text(row, "action_type"),
                text(row, "source_type"),
                text(row, "status"),
                localDateTime(row.get("occurred_at")),
                text(row, "title"),
                text(row, "before_value"),
                text(row, "after_value"),
                text(row, "note"),
                text(row, "external_reference"),
                localDateTime(row.get("created_at")),
                localDateTime(row.get("updated_at")),
                row.get("review_task_id") == null ? null : longNumber(row, "review_task_id")
        );
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value, label, 40).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(label + "不受支持：" + value);
        }
        return normalized;
    }

    private static String optionalEnum(String value, Set<String> allowed, String defaultValue, String label) {
        return blank(value) ? defaultValue : enumValue(value, allowed, label);
    }

    private static String required(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空。");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符。");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String label) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符。");
        }
        return normalized;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static double number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static long longNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().atStartOfDay();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private record TargetIdentity(String parentSku, String targetName) {
    }

    public record CreateCommand(
            Long reviewTaskId,
            String targetType,
            String targetKey,
            List<String> targetKeys,
            String actionType,
            String sourceType,
            String status,
            LocalDateTime occurredAt,
            String title,
            String beforeValue,
            String afterValue,
            String note,
            String externalReference
    ) {
    }

    public record OperationRecord(
            long id,
            String targetType,
            String targetKey,
            List<String> targetKeys,
            String parentSku,
            String targetName,
            String actionType,
            String sourceType,
            String status,
            LocalDateTime occurredAt,
            String title,
            String beforeValue,
            String afterValue,
            String note,
            String externalReference,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long reviewTaskId
    ) {
    }

    public record TargetOption(
            String targetType,
            String targetKey,
            String parentSku,
            String targetName
    ) {
    }

    /**
     * 款内 SKU 候选（新增运营记录时勾选用）。
     */
    public record TargetSkuOption(
            String sku,
            String itemId,
            String size,
            String color,
            String name
    ) {
    }

    public record MetricSnapshot(
            LocalDate startDate,
            LocalDate endDate,
            int calendarDays,
            double units,
            double sales,
            double impressions,
            double clicks,
            double adSpend,
            double adOrders,
            double adSales,
            double roas,
            double cpc,
            double unitsPerDay,
            double salesPerDay,
            double adSpendPerDay,
            double adSalesPerDay
    ) {
    }

    public record OperationImpact(
            String state,
            int requestedDays,
            int comparedDays,
            LocalDate latestDataDate,
            MetricSnapshot before,
            MetricSnapshot after,
            int overlappingOperations,
            String message
    ) {
    }

    public record OperationWithImpact(
            OperationRecord record,
            OperationImpact impact
    ) {
    }

    public record OperationMeta(
            int requestedDays,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate latestDataDate,
            long recordCount,
            long executedCount,
            long systemCount,
            long readyCount
    ) {
    }

    public record OperationPayload(
            OperationMeta meta,
            List<OperationWithImpact> items
    ) {
    }

    public record Checkpoint(
            Integer afterDays,
            String label
    ) {
    }

    public record ReviewTaskRef(long id, String parentSku) {
    }

    public record TaskBrief(
            long id,
            String parentSku,
            String title,
            String status
    ) {
    }

    public record TaskCheckpoint(
            long id,
            long taskId,
            LocalDate dueDate,
            String label,
            String status,
            LocalDateTime resolvedAt,
            String computedStatus
    ) {
    }

    public record ReminderWithTask(
            TaskCheckpoint reminder,
            TaskBrief task,
            String computedStatus
    ) {
    }

    public record ReminderPayload(
            long dueCount,
            List<ReminderWithTask> items
    ) {
    }

    public record CreateTaskCommand(
            String parentSku,
            String title,
            String goal,
            LocalDateTime startedAt,
            List<Checkpoint> checkpoints
    ) {
    }

    public record TaskProgress(int done, int total) {
    }

    public record ReviewTask(
            long id,
            String parentSku,
            String title,
            String goal,
            String status,
            LocalDateTime startedAt,
            LocalDateTime closedAt,
            String conclusion,
            TaskProgress progress,
            List<TaskCheckpoint> checkpoints,
            List<OperationWithImpact> records
    ) {
    }

    public record OperationGroup(
            String parentSku,
            String productName,
            LocalDateTime lastActivityAt,
            int recordCount,
            int activeTaskCount,
            List<ReviewTask> tasks
    ) {
    }

    public record BoardMeta(
            int requestedDays,
            LocalDate latestDataDate,
            long recordCount,
            long executedCount,
            long activeTaskCount,
            long dueCheckpointCount
    ) {
    }

    public record BoardPayload(
            BoardMeta meta,
            List<OperationGroup> groups
    ) {
    }
}
