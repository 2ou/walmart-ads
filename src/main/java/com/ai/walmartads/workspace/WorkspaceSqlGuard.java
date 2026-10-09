package com.ai.walmartads.workspace;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the active workspace discriminator to direct JDBC statements.
 *
 * <p>JPA entities are protected by Hibernate {@code @TenantId}; this guard covers the sizeable set of
 * hand-written analytical SQL without trusting every future query author to remember a predicate.
 * Reads use mergeable derived tables so every FROM/JOIN occurrence is scoped, including subqueries.
 * Writes receive a literal tenant id (a server-side long, never user text) and fail closed when a statement
 * shape cannot be scoped safely.</p>
 */
public class WorkspaceSqlGuard {

    private static final Set<String> TENANT_TABLES = Set.of(
            "wm_ads_import_batch", "wm_ads_import_file", "wm_ads_order_record", "wm_ads_product_record",
            "wm_ads_rule_result", "wm_ads_ai_analysis", "wm_ads_inventory_supply_plan", "wm_ads_sku_mapping",
            "wm_ads_operation_record", "wm_ads_return_order", "wm_ads_profit_assumption", "wm_ads_demand_forecast",
            "wm_ads_production_order", "wm_ads_forecast_factor", "wm_ads_forecast_factor_daily_parent",
            "wm_ads_shipment_record", "wm_ads_shipment_logistics", "wm_ads_transit_channel_lead_time",
            "wm_ads_replenishment_decision", "wm_ads_domestic_inventory_snapshot", "wm_ads_parent_lifecycle",
            "wm_ads_campaign_decision_config", "wm_ads_operation_reminder", "wm_ads_review_task",
            "wm_ads_wfs_storage_fee", "wm_ads_wfs_fulfillment_fee", "wm_ads_walmart_listing_sync_log",
            "wm_ads_lingxing_order_sync_log", "wm_ads_order_record_valid", "wm_ads_lx_ad_report_raw", "wm_ads_lx_ad_report",
            "wm_ads_lx_ad_slice", "wm_ads_lx_ad_sync_log", "wm_ads_lx_item_sku_alias",
            "wm_ads_lx_inventory_snapshot", "wm_ads_inventory_unified", "wm_ads_lx_comment_raw",
            "wm_ads_lingxing_comment_sync_log", "wm_ads_forecast_setting", "wm_ads_ai_agent_run",
            "wm_ads_ai_conversation", "wm_ads_ai_conversation_message", "wm_ads_price_baseline",
            "wm_ads_price_change", "wm_ads_supply_production_order", "wm_ads_supply_delivery_detail",
            "wm_ads_supply_chain_sync_log", "wm_ads_dashboard_ad_daily", "wm_ads_dashboard_ad_sku_daily",
            "wm_ads_dashboard_ad_campaign_daily");

    private static final String TABLE_ALTERNATION = TENANT_TABLES.stream()
            .sorted((left, right) -> Integer.compare(right.length(), left.length()))
            .map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElseThrow();
    private static final Pattern READ_TABLE = Pattern.compile(
            "(?i)\\b(from|join)\\s+(" + TABLE_ALTERNATION + ")\\b");
    private static final Pattern COMMA_TABLE = Pattern.compile(
            "(?i)(,)\\s*(" + TABLE_ALTERNATION + ")\\b");
    private static final Pattern TARGET_TABLE = Pattern.compile(
            "(?is)^\\s*(insert(?:\\s+ignore)?\\s+into|replace\\s+into|update|delete\\s+from)\\s+(" + TABLE_ALTERNATION + ")\\b");
    private static final Pattern INSERT_VALUES = Pattern.compile(
            "(?is)^\\s*(insert(?:\\s+ignore)?\\s+into)\\s+(" + TABLE_ALTERNATION + ")\\s*\\(([^)]*)\\)\\s*values\\s*\\(");
    private static final Pattern UPDATE_TARGET = Pattern.compile(
            "(?is)^\\s*update\\s+(" + TABLE_ALTERNATION + ")\\s*(?:as\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)?");
    private static final Pattern DELETE_TARGET = Pattern.compile(
            "(?is)^\\s*delete\\s+from\\s+(" + TABLE_ALTERNATION + ")\\s*(?:as\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)?");
    private static final Pattern WHERE = Pattern.compile("(?i)\\bwhere\\b");
    private static final List<String> NON_ALIAS_WORDS = List.of(
            "set", "join", "left", "right", "inner", "outer", "cross", "full", "natural", "straight_join",
            "where", "group", "order", "having", "limit", "union", "on", "and", "or", "window", "qualify",
            "use", "force", "ignore", "partition", "for", "lock", "values");
    private static final Pattern FOLLOWING_ALIAS = Pattern.compile(
            "(?is)^\\s+(?:as\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)");

    public String rewrite(String sql) {
        if (sql == null || sql.isBlank() || WorkspaceContext.current().isEmpty()) return sql;
        long workspaceId = WorkspaceContext.requireId();
        String normalized = stripLeadingComments(sql).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("select") || normalized.startsWith("with") || normalized.startsWith("explain")) {
            return scopeReads(sql, workspaceId);
        }
        Matcher target = TARGET_TABLE.matcher(sql);
        if (!target.find()) {
            return sql;
        }
        if (normalized.startsWith("insert")) return scopeInsert(sql, workspaceId);
        if (normalized.startsWith("update")) return scopeUpdate(sql, workspaceId);
        if (normalized.startsWith("delete")) return scopeDelete(sql, workspaceId);
        throw new IllegalStateException("拒绝执行无法安全限定工作区的 SQL");
    }

    String scopeReads(String sql, long workspaceId) {
        String result = replaceReadTables(sql, READ_TABLE, workspaceId);
        return replaceReadTables(result, COMMA_TABLE, workspaceId);
    }

    private String replaceReadTables(String sql, Pattern pattern, long workspaceId) {
        Matcher matcher = pattern.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String table = matcher.group(2);
            String generatedAlias = hasFollowingAlias(sql, matcher.end()) ? "" : " as " + table;
            String replacement = prefix + " (select * from " + table + " where workspace_id = " + workspaceId + ")"
                    + generatedAlias;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    String scopeInsert(String sql, long workspaceId) {
        Matcher matcher = INSERT_VALUES.matcher(sql);
        if (!matcher.find()) {
            if (containsWorkspaceColumn(sql)) return sql;
            throw new IllegalStateException("租户表 INSERT 必须使用显式列清单与 VALUES");
        }
        if (containsColumn(matcher.group(3), "workspace_id")) return sql;
        String replacement = matcher.group(1) + " " + matcher.group(2)
                + " (workspace_id, " + matcher.group(3) + ") values (" + workspaceId + ", ";
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    String scopeUpdate(String sql, long workspaceId) {
        Matcher target = UPDATE_TARGET.matcher(sql);
        if (!target.find()) throw new IllegalStateException("无法识别租户 UPDATE 目标");
        String table = target.group(1);
        String alias = safeAlias(target.group(2), table);
        String scopedTarget = addWherePredicate(sql, alias + ".workspace_id = " + workspaceId);
        return scopeJoinTables(scopedTarget, workspaceId);
    }

    String scopeDelete(String sql, long workspaceId) {
        Matcher target = DELETE_TARGET.matcher(sql);
        if (!target.find()) throw new IllegalStateException("无法识别租户 DELETE 目标");
        String table = target.group(1);
        String alias = safeAlias(target.group(2), table);
        String scopedTarget = addWherePredicate(sql, alias + ".workspace_id = " + workspaceId);
        return scopeJoinTables(scopedTarget, workspaceId);
    }

    private String scopeJoinTables(String sql, long workspaceId) {
        Matcher matcher = Pattern.compile("(?i)\\b(join)\\s+(" + TABLE_ALTERNATION + ")\\b").matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String table = matcher.group(2);
            String generatedAlias = hasFollowingAlias(sql, matcher.end()) ? "" : " as " + table;
            String replacement = matcher.group(1) + " (select * from " + matcher.group(2)
                    + " where workspace_id = " + workspaceId + ")" + generatedAlias;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String addWherePredicate(String sql, String predicate) {
        Matcher where = WHERE.matcher(sql);
        if (where.find()) {
            return sql.substring(0, where.end()) + " " + predicate + " and " + sql.substring(where.end());
        }
        Matcher tail = Pattern.compile("(?i)\\b(order\\s+by|limit)\\b").matcher(sql);
        if (tail.find()) {
            return sql.substring(0, tail.start()) + " where " + predicate + " " + sql.substring(tail.start());
        }
        return sql + " where " + predicate;
    }

    private static String safeAlias(String alias, String table) {
        if (alias == null || NON_ALIAS_WORDS.contains(alias.toLowerCase(Locale.ROOT))) return table;
        return alias;
    }

    private static boolean hasFollowingAlias(String sql, int tableEnd) {
        Matcher alias = FOLLOWING_ALIAS.matcher(sql.substring(tableEnd));
        return alias.find() && !NON_ALIAS_WORDS.contains(alias.group(1).toLowerCase(Locale.ROOT));
    }

    private static boolean containsColumn(String columns, String expected) {
        return List.of(columns.split(",")).stream().map(String::trim)
                .anyMatch(column -> column.equalsIgnoreCase(expected) || column.endsWith("." + expected));
    }

    private static boolean containsWorkspaceColumn(String sql) {
        return Pattern.compile("(?i)\\bworkspace_id\\b").matcher(sql).find();
    }

    private static String stripLeadingComments(String sql) {
        return sql.replaceFirst("(?s)^\\s*(?:/\\*.*?\\*/\\s*|--[^\\r\\n]*(?:\\r?\\n|$)\\s*)*", "").trim();
    }
}
