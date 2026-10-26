package com.ai.walmartads.connector;

import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class DataConnectorStore {

    private final JdbcTemplate jdbc;

    public DataConnectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void ensureWorkspace(WorkspacePrincipal principal) {
        for (ConnectorSource source : ConnectorSource.values()) {
            boolean enabled = source != ConnectorSource.SUPPLY_CHAIN || principal.supplyChainEnabled();
            jdbc.update("""
                    insert into wm_ads_connector_source_state(workspace_id,source_code,enabled,status)
                    values (?,?,?,'WAITING')
                    on duplicate key update enabled=values(enabled)
                    """, principal.workspaceId(), source.name(), enabled);
        }
        for (ConnectorSystem system : ConnectorSystem.values()) {
            jdbc.update("""
                    insert into wm_ads_connector_session(workspace_id,system_code,status)
                    values (?,?,'LOGIN_REQUIRED')
                    on duplicate key update workspace_id=values(workspace_id)
                    """, principal.workspaceId(), system.name());
        }
    }

    public SessionRow session(long workspaceId, ConnectorSystem system) {
        List<SessionRow> rows = jdbc.query("""
                select encrypted_state, encrypted_credential, status, last_login_at,
                       last_validated_at, last_message
                  from wm_ads_connector_session where workspace_id=? and system_code=?
                """, (rs, rowNum) -> new SessionRow(
                rs.getString("encrypted_state"), rs.getString("encrypted_credential"),
                rs.getString("status"), time(rs.getTimestamp("last_login_at")),
                time(rs.getTimestamp("last_validated_at")), rs.getString("last_message")),
                workspaceId, system.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void saveSession(long workspaceId, ConnectorSystem system, String encryptedState,
                            String encryptedCredential, String message, boolean login) {
        jdbc.update("""
                insert into wm_ads_connector_session(
                    workspace_id,system_code,encrypted_state,encrypted_credential,status,
                    last_login_at,last_validated_at,last_message
                ) values (?,?,?,?,'CONNECTED',if(?,now(6),null),now(6),?)
                on duplicate key update
                    encrypted_state=values(encrypted_state),
                    encrypted_credential=coalesce(values(encrypted_credential),encrypted_credential),
                    status='CONNECTED',
                    last_login_at=if(?,now(6),last_login_at),
                    last_validated_at=now(6),last_message=values(last_message)
                """, workspaceId, system.name(), encryptedState, encryptedCredential,
                login, message, login);
    }

    public void invalidateSession(long workspaceId, ConnectorSystem system, String message,
                                  boolean clearCredential) {
        jdbc.update("""
                update wm_ads_connector_session
                   set status='LOGIN_REQUIRED', last_message=?,
                       encrypted_credential=if(?,null,encrypted_credential)
                 where workspace_id=? and system_code=?
                """, truncate(message, 1000), clearCredential, workspaceId, system.name());
    }

    @Transactional
    public long startRun(long workspaceId, ConnectorSource source, String triggeredBy) {
        jdbc.update("""
                update wm_ads_connector_source_state
                   set status='RUNNING',last_started_at=now(6),last_message=null,last_triggered_by=?
                 where workspace_id=? and source_code=?
                """, triggeredBy, workspaceId, source.name());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into wm_ads_connector_run(workspace_id,source_code,triggered_by,status,started_at)
                    values (?,?,?,'RUNNING',now(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, workspaceId);
            statement.setString(2, source.name());
            statement.setString(3, triggeredBy);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("无法创建数据连接运行记录");
        return key.longValue();
    }

    @Transactional
    public void finishRun(long runId, long workspaceId, ConnectorSource source, String status,
                          int recordCount, String message) {
        boolean success = "SUCCESS".equals(status);
        jdbc.update("""
                update wm_ads_connector_run
                   set status=?,finished_at=now(6),record_count=?,message=? where id=?
                """, status, recordCount, truncate(message, 2000), runId);
        jdbc.update("""
                update wm_ads_connector_source_state
                   set status=?,
                       last_success_at=if(?,now(6),last_success_at),
                       last_failure_at=if(?,last_failure_at,now(6)),
                       last_record_count=if(?,?,last_record_count),last_message=?
                 where workspace_id=? and source_code=?
                """, status, success, success, success, recordCount,
                truncate(message, 1000), workspaceId, source.name());
    }

    public boolean succeededToday(long workspaceId, ConnectorSource source, LocalDate today) {
        Integer count = jdbc.queryForObject("""
                select count(*) from wm_ads_connector_source_state
                 where workspace_id=? and source_code=? and enabled=1
                   and last_success_at>=? and last_success_at<?
                """, Integer.class, workspaceId, source.name(), today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        return count != null && count > 0;
    }

    public List<SourceState> sourceStates(long workspaceId) {
        return jdbc.query("""
                select source_code,enabled,status,last_started_at,last_success_at,last_failure_at,
                       last_message,last_record_count,last_triggered_by
                  from wm_ads_connector_source_state where workspace_id=? order by source_code
                """, (rs, rowNum) -> new SourceState(
                rs.getString("source_code"), rs.getBoolean("enabled"), rs.getString("status"),
                time(rs.getTimestamp("last_started_at")), time(rs.getTimestamp("last_success_at")),
                time(rs.getTimestamp("last_failure_at")), rs.getString("last_message"),
                rs.getInt("last_record_count"), rs.getString("last_triggered_by")), workspaceId);
    }

    public List<RunRow> recentRuns(long workspaceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                select id,source_code,triggered_by,status,started_at,finished_at,record_count,message
                  from wm_ads_connector_run where workspace_id=? order by id desc limit ?
                """, (rs, rowNum) -> new RunRow(
                rs.getLong("id"), rs.getString("source_code"), rs.getString("triggered_by"),
                rs.getString("status"), time(rs.getTimestamp("started_at")),
                time(rs.getTimestamp("finished_at")), rs.getInt("record_count"),
                rs.getString("message")), workspaceId, safeLimit);
    }

    public void cleanupRuns(int retentionDays) {
        jdbc.update("delete from wm_ads_connector_run where started_at<date_sub(now(6),interval ? day)",
                Math.max(1, retentionDays));
    }

    /** JVM 已退出时不可能仍有任务运行；启动后关闭遗留的假 RUNNING 审计。 */
    public int recoverAbandonedRuns() {
        return jdbc.update("""
                update wm_ads_connector_run
                   set status='FAILED', finished_at=now(6),
                       message=coalesce(message,'服务重启，上一轮同步已中断')
                 where status='RUNNING'
                """);
    }

    private static LocalDateTime time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record SessionRow(String encryptedState, String encryptedCredential, String status,
                             LocalDateTime lastLoginAt, LocalDateTime lastValidatedAt, String message) {
    }

    public record SourceState(String sourceCode, boolean enabled, String status,
                              LocalDateTime lastStartedAt, LocalDateTime lastSuccessAt,
                              LocalDateTime lastFailureAt, String message, int recordCount,
                              String triggeredBy) {
    }

    public record RunRow(long id, String sourceCode, String triggeredBy, String status,
                         LocalDateTime startedAt, LocalDateTime finishedAt, int recordCount,
                         String message) {
    }
}
