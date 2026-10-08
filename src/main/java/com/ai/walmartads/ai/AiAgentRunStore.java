package com.ai.walmartads.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AiAgentRunStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AiAgentRunStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long create(String requestedSkill, String question, String scopePayload, String modelName) {
        return create(requestedSkill, question, scopePayload, modelName, null);
    }

    public long create(
            String requestedSkill,
            String question,
            String scopePayload,
            String modelName,
            Long conversationId
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statement = connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into wm_ads_ai_agent_run
                        (conversation_id, requested_skill, question, scope_payload, provider, model_name, status, created_at)
                    values (?, ?, ?, ?, 'kie', ?, 'RUNNING', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (conversationId == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, conversationId);
            ps.setString(2, requestedSkill);
            ps.setString(3, question);
            ps.setString(4, scopePayload);
            ps.setString(5, modelName);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        };
        jdbcTemplate.update(statement, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法创建 Agent 运行记录");
        }
        return key.longValue();
    }

    public void succeed(
            long id,
            AiSkillService.SkillDefinition skill,
            String toolTrace,
            String responsePayload,
            String modelName,
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {
        jdbcTemplate.update("""
                update wm_ads_ai_agent_run
                   set activated_skill = ?, skill_version = ?, tool_trace = ?, response_payload = ?,
                       model_name = ?, status = 'SUCCESS', prompt_tokens = ?, completion_tokens = ?,
                       total_tokens = ?, completed_at = ?
                 where id = ?
                """, skill.code(), skill.version(), toolTrace, responsePayload, modelName,
                promptTokens, completionTokens, totalTokens, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void fail(long id, String activatedSkill, String toolTrace, String errorMessage) {
        jdbcTemplate.update("""
                update wm_ads_ai_agent_run
                   set activated_skill = ?, tool_trace = ?, status = 'FAILED', error_message = ?, completed_at = ?
                 where id = ?
                """, activatedSkill, toolTrace, truncate(errorMessage, 4000), Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void updateTrace(long id, String toolTrace) {
        jdbcTemplate.update("""
                update wm_ads_ai_agent_run
                   set tool_trace = ?
                 where id = ? and status = 'RUNNING'
                """, toolTrace, id);
    }

    public Optional<RunView> latestSuccessful() {
        return jdbcTemplate.query("""
                select id, requested_skill, activated_skill, skill_version, question, scope_payload,
                       tool_trace, response_payload, provider, model_name, status,
                       prompt_tokens, completion_tokens, total_tokens, error_message, created_at, completed_at
                  from wm_ads_ai_agent_run
                 where status = 'SUCCESS'
                 order by created_at desc, id desc
                 limit 1
                """, (rs, rowNum) -> map(rs)).stream().findFirst();
    }

    public Optional<RunView> find(long id) {
        return jdbcTemplate.query("""
                select id, requested_skill, activated_skill, skill_version, question, scope_payload,
                       tool_trace, response_payload, provider, model_name, status,
                       prompt_tokens, completion_tokens, total_tokens, error_message, created_at, completed_at
                  from wm_ads_ai_agent_run
                 where id = ?
                """, (rs, rowNum) -> map(rs), id).stream().findFirst();
    }

    public Optional<RunView> findLatestFailed(long conversationId, LocalDateTime before) {
        return jdbcTemplate.query("""
                select id, requested_skill, activated_skill, skill_version, question, scope_payload,
                       tool_trace, response_payload, provider, model_name, status,
                       prompt_tokens, completion_tokens, total_tokens, error_message, created_at, completed_at
                  from wm_ads_ai_agent_run
                 where conversation_id = ? and status = 'FAILED' and created_at <= ?
                 order by created_at desc, id desc
                 limit 1
                """, (rs, rowNum) -> map(rs), conversationId, Timestamp.valueOf(before)).stream().findFirst();
    }

    public List<RunView> recent(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        return jdbcTemplate.query("""
                select id, requested_skill, activated_skill, skill_version, question, scope_payload,
                       tool_trace, response_payload, provider, model_name, status,
                       prompt_tokens, completion_tokens, total_tokens, error_message, created_at, completed_at
                  from wm_ads_ai_agent_run
                 order by created_at desc, id desc
                 limit ?
                """, (rs, rowNum) -> map(rs), limit);
    }

    private RunView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunView(
                rs.getLong("id"),
                rs.getString("requested_skill"),
                rs.getString("activated_skill"),
                (Integer) rs.getObject("skill_version"),
                rs.getString("question"),
                json(rs.getString("scope_payload")),
                json(rs.getString("tool_trace")),
                json(rs.getString("response_payload")),
                rs.getString("provider"),
                rs.getString("model_name"),
                rs.getString("status"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getInt("total_tokens"),
                rs.getString("error_message"),
                timestamp(rs.getTimestamp("created_at")),
                timestamp(rs.getTimestamp("completed_at"))
        );
    }

    private JsonNode json(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Agent 运行记录包含无效 JSON", exception);
        }
    }

    private static LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record RunView(
            long id,
            String requestedSkill,
            String activatedSkill,
            Integer skillVersion,
            String question,
            JsonNode scope,
            JsonNode toolTrace,
            JsonNode response,
            String provider,
            String model,
            String status,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String error,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }
}
