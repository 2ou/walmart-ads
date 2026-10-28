package com.ai.walmartads.workspace;

import com.ai.walmartads.config.LingXingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;
import java.sql.PreparedStatement;
import java.sql.Statement;

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

    /** Acquire a database lease so restarts or multiple app instances cannot run the same category twice. */
    public SyncLease tryStartSync(long workspaceId, String category, String triggerSource) {
        String token = UUID.randomUUID().toString();
        int acquired = jdbc.update("""
                update wm_ads_workspace_sync_setting
                   set last_started_at=now(6), last_status='RUNNING', last_message=null,
                       lease_token=?, lease_until=now(6) + interval 30 minute
                 where workspace_id=? and sync_category=?
                   and (lease_until is null or lease_until < now(6))
                """, token, workspaceId, category);
        if (acquired == 0) return null;
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into wm_ads_workspace_sync_run
                        (workspace_id,sync_category,trigger_source,status)
                    values (?,?,?,'RUNNING')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, workspaceId);
            statement.setString(2, category);
            statement.setString(3, valueOr(triggerSource, "CRON"));
            return statement;
        }, holder);
        Number runId = holder.getKey();
        return new SyncLease(workspaceId, category, token, runId == null ? 0L : runId.longValue(), System.nanoTime());
    }

    public void finishSync(SyncLease lease, String status, String message, long fetched, long written,
                           String integrityStatus, LocalDateTime nextRetryAt) {
        if (lease == null) return;
        boolean valid = "SUCCESS".equalsIgnoreCase(status) || "PARTIAL".equalsIgnoreCase(status);
        jdbc.update("""
                update wm_ads_workspace_sync_setting
                   set last_completed_at=now(6), last_status=?, last_message=?,
                       last_valid_data_at=if(?,now(6),last_valid_data_at),
                       last_fetched_count=?,last_written_count=?,integrity_status=?,next_retry_at=?,
                       lease_token=null,lease_until=null
                 where workspace_id=? and sync_category=? and lease_token=?
                """, status, truncate(message, 1000), valid, fetched, written, integrityStatus, nextRetryAt,
                lease.workspaceId(), lease.category(), lease.token());
        long durationMs = Math.max(0L, (System.nanoTime() - lease.startedNanos()) / 1_000_000L);
        jdbc.update("""
                update wm_ads_workspace_sync_run
                   set status=?,fetched_count=?,written_count=?,integrity_status=?,message=?,
                       completed_at=now(6),duration_ms=?
                 where id=?
                """, status, fetched, written, integrityStatus, truncate(message, 1000), durationMs, lease.runId());
    }

    public void markSyncFinished(long workspaceId, String category, boolean success, String message) {
        jdbc.update("""
                update wm_ads_workspace_sync_setting
                   set last_completed_at=now(6), last_status=?, last_message=?,
                       last_valid_data_at=if(?,now(6),last_valid_data_at),
                       integrity_status=if(?,'VALID',coalesce(integrity_status,'FAILED')),
                       lease_token=null,lease_until=null
                 where workspace_id=? and sync_category=?
                """, success ? "SUCCESS" : "FAILED", truncate(message, 1000), success, success,
                workspaceId, category);
    }

    /**
     * 限流冷却导致的跳过：不是同步失败，只是「等一会儿再来」。
     *
     * <p>写成独立的 {@code DEFERRED} 状态而不是 {@code FAILED}，否则页面每个整点都会因为
     * 冷却窗而报红，运营无法区分「真的坏了」和「在排队」。{@code last_completed_at} 保持不动，
     * 因为它记录的是最后一次真实跑完的时间。
     */
    public void markSyncDeferred(long workspaceId, String category, String message) {
        jdbc.update("""
                update wm_ads_workspace_sync_setting
                   set last_status='DEFERRED', last_message=?,lease_token=null,lease_until=null
                 where workspace_id=? and sync_category=?
                """, truncate(message, 1000), workspaceId, category);
    }

    /**
     * 找出「卡在 RUNNING 但没有进程在跑」的同步项。
     *
     * <p>进程被重启/杀掉时 {@code markSyncFinished} 不会执行，状态会永久停在 RUNNING
     * （HISTORY 从 2026-10-09 一直卡到现在的直接原因）。超过阈值即视为僵尸记录。
     */
    public List<Long> staleRunningWorkspaces(String category, int staleMinutes) {
        return jdbc.queryForList("""
                select workspace_id
                  from wm_ads_workspace_sync_setting
                 where sync_category=? and last_status='RUNNING'
                   and last_started_at < now(6) - interval ? minute
                """, Long.class, category, Math.max(1, staleMinutes));
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

    public boolean claimInitializationAttempt(long workspaceId) {
        return jdbc.update("""
                update wm_ads_workspace
                   set initialization_last_attempt_at=now(6),
                       initialization_next_attempt_at=now(6) + interval 6 hour
                 where id=? and (initialization_next_attempt_at is null
                                  or initialization_next_attempt_at <= now(6))
                """, workspaceId) > 0;
    }

    public void deferInitializationUntil(long workspaceId, LocalDateTime nextAttemptAt) {
        jdbc.update("update wm_ads_workspace set initialization_next_attempt_at=? where id=?",
                nextAttemptAt, workspaceId);
    }

    public boolean hasEstablishedData(long workspaceId) {
        Integer count = jdbc.queryForObject("""
                select (select count(*) from wm_ads_product_record
                         where workspace_id=? and current_version=true)
                     + (select count(*) from wm_ads_lx_inventory_snapshot where workspace_id=?)
                """, Integer.class, workspaceId, workspaceId);
        return count != null && count > 0;
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

    public record SyncLease(long workspaceId, String category, String token, long runId, long startedNanos) { }
}
