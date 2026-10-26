package com.ai.walmartads.workspace;

import com.ai.walmartads.config.LingXingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class WorkspaceLingXingConfigService {

    private final JdbcTemplate jdbc;
    private final LingXingProperties legacy;

    public WorkspaceLingXingConfigService(JdbcTemplate jdbc, LingXingProperties legacy) {
        this.jdbc = jdbc;
        this.legacy = legacy;
    }

    public Config current() {
        long workspaceId = WorkspaceContext.current().map(WorkspacePrincipal::workspaceId).orElse(1L);
        List<Config> rows = jdbc.query("""
                select id, mcp_base_url, mcp_key, store_id
                  from wm_ads_workspace where id=? and status='ACTIVE'
                """, (rs, rowNum) -> new Config(
                rs.getLong("id"), valueOr(rs.getString("mcp_base_url"), legacy.resolvedBaseUrl()),
                rs.getString("mcp_key"), rs.getString("store_id"), legacy.resolvedPageSize(),
                advertiserIds(rs.getLong("id"))), workspaceId);
        if (!rows.isEmpty()) return rows.getFirst();
        return new Config(workspaceId, legacy.resolvedBaseUrl(), legacy.apiKey(), legacy.resolvedStoreId(),
                legacy.resolvedPageSize(), legacy.resolvedAdvertiserIds());
    }

    public boolean currentConfigured() {
        return current().configured();
    }

    public List<WorkspacePrincipal> activeWorkspaces(String category, String cadence, Integer staggerMinute) {
        StringBuilder sql = new StringBuilder("""
                select w.id, w.username, w.status, w.store_id, w.store_name, w.store_abbreviation, w.supply_chain_enabled
                  from wm_ads_workspace w
                  join wm_ads_workspace_sync_setting s on s.workspace_id=w.id
                 where w.status='ACTIVE' and w.store_id is not null and w.mcp_key is not null
                   and s.sync_category=? and s.cadence=? and s.enabled=1
                """);
        if (staggerMinute != null) sql.append(" and s.stagger_minute=?");
        sql.append(" order by w.id");
        Object[] args = staggerMinute == null
                ? new Object[]{category, cadence}
                : new Object[]{category, cadence, staggerMinute};
        return jdbc.query(sql.toString(), (rs, rowNum) -> new WorkspacePrincipal(
                rs.getLong("id"), rs.getString("username"), rs.getString("status"),
                rs.getString("store_id"), rs.getString("store_name"), rs.getString("store_abbreviation"),
                rs.getBoolean("supply_chain_enabled"), 0L), args);
    }

    public List<WorkspacePrincipal> initializingWorkspaces() {
        return jdbc.query("""
                select id, username, status, store_id, store_name, store_abbreviation, supply_chain_enabled
                  from wm_ads_workspace
                 where status='ACTIVE' and initialization_status in ('SYNCING','FAILED')
                   and store_id is not null and mcp_key is not null
                 order by id
                """, (rs, rowNum) -> new WorkspacePrincipal(
                rs.getLong("id"), rs.getString("username"), rs.getString("status"),
                rs.getString("store_id"), rs.getString("store_name"), rs.getString("store_abbreviation"),
                rs.getBoolean("supply_chain_enabled"), 0L));
    }

    public List<WorkspacePrincipal> supplyChainWorkspaces() {
        return jdbc.query("""
                select id, username, status, store_id, store_name, store_abbreviation, supply_chain_enabled
                  from wm_ads_workspace
                 where status='ACTIVE' and supply_chain_enabled=1 order by id
                """, (rs, rowNum) -> new WorkspacePrincipal(
                rs.getLong("id"), rs.getString("username"), rs.getString("status"),
                rs.getString("store_id"), rs.getString("store_name"), rs.getString("store_abbreviation"),
                true, 0L));
    }

    /** 项目内置连接器覆盖全部已启用店铺；供应链来源再读取 principal 上的独立开关。 */
    public List<WorkspacePrincipal> connectorWorkspaces() {
        return jdbc.query("""
                select id, username, status, store_id, store_name, store_abbreviation, supply_chain_enabled
                  from wm_ads_workspace
                 where status='ACTIVE' and store_id is not null
                 order by id
                """, (rs, rowNum) -> new WorkspacePrincipal(
                rs.getLong("id"), rs.getString("username"), rs.getString("status"),
                rs.getString("store_id"), rs.getString("store_name"), rs.getString("store_abbreviation"),
                rs.getBoolean("supply_chain_enabled"), 0L));
    }

    public void markSyncStarted(long workspaceId, String category) {
        jdbc.update("""
                update wm_ads_workspace_sync_setting
                   set last_started_at=now(6), last_status='RUNNING', last_message=null
                 where workspace_id=? and sync_category=?
                """, workspaceId, category);
    }

    public void markSyncFinished(long workspaceId, String category, boolean success, String message) {
        jdbc.update("""
                update wm_ads_workspace_sync_setting
                   set last_completed_at=now(6), last_status=?, last_message=?
                 where workspace_id=? and sync_category=?
                """, success ? "SUCCESS" : "FAILED", truncate(message, 1000), workspaceId, category);
    }

    public void markInitialization(long workspaceId, String status, String message) {
        jdbc.update("""
                update wm_ads_workspace
                   set initialization_status=?, initialization_message=?,
                       initialization_started_at=coalesce(initialization_started_at,now(6)),
                       initialization_completed_at=if(?='READY',now(6),initialization_completed_at)
                 where id=?
                """, status, truncate(message, 1000), status, workspaceId);
    }

    private List<Long> advertiserIds(long workspaceId) {
        return jdbc.queryForList("select advertiser_id from wm_ads_workspace_advertiser where workspace_id=? order by advertiser_id",
                Long.class, workspaceId);
    }

    private static String valueOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    public record Config(long workspaceId, String baseUrl, String apiKey, String storeId, int pageSize,
                         List<Long> advertiserIds) {
        public boolean configured() {
            return StringUtils.hasText(apiKey) && StringUtils.hasText(storeId);
        }
    }
}
