package com.ai.walmartads.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AiConversationStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AiConversationStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long create(String requestedSkill, AdvertisingAgentTools.Scope scope, String model) {
        String scopePayload = writeJson(scope);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statement = connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into wm_ads_ai_conversation
                        (title, requested_skill, model_name, scope_payload, status, created_at, updated_at)
                    values ('新对话', ?, ?, ?, 'ACTIVE', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            LocalDateTime now = LocalDateTime.now();
            ps.setString(1, requestedSkill);
            ps.setString(2, model);
            ps.setString(3, scopePayload);
            ps.setTimestamp(4, Timestamp.valueOf(now));
            ps.setTimestamp(5, Timestamp.valueOf(now));
            return ps;
        };
        jdbcTemplate.update(statement, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法创建 Agent 对话");
        }
        return key.longValue();
    }

    public void updateContext(long id, String requestedSkill, AdvertisingAgentTools.Scope scope, String model) {
        int updated = jdbcTemplate.update("""
                update wm_ads_ai_conversation
                   set requested_skill = ?, model_name = ?, scope_payload = ?, updated_at = ?
                 where id = ? and status = 'ACTIVE'
                """, requestedSkill, model, writeJson(scope), Timestamp.valueOf(LocalDateTime.now()), id);
        if (updated == 0) {
            throw new IllegalArgumentException("Agent 对话不存在或已归档: " + id);
        }
    }

    public long addUserMessage(long conversationId, String question) {
        long id = insertMessage(conversationId, "USER", question, null, null, "SUCCESS", null);
        touchConversation(conversationId, preview(question), title(question));
        return id;
    }

    public long addAssistantMessage(long conversationId, long runId, JsonNode response) {
        String payload = response == null ? null : writeJson(response);
        String summary = response == null ? "分析完成" : response.path("summary").asText("分析完成");
        long id = insertMessage(conversationId, "ASSISTANT", null, payload, runId, "SUCCESS", null);
        touchConversation(conversationId, preview(summary), null);
        return id;
    }

    public long addErrorMessage(long conversationId, String error) {
        return addErrorMessage(conversationId, null, error);
    }

    public long addErrorMessage(long conversationId, Long runId, String error) {
        String message = StringUtils.hasText(error) ? error.trim() : "Agent 分析失败";
        long id = insertMessage(conversationId, "ASSISTANT", "分析失败：" + message,
                null, runId, "FAILED", truncate(message, 4000));
        touchConversation(conversationId, preview("分析失败：" + message), null);
        return id;
    }

    public Optional<ConversationRow> find(long id) {
        return jdbcTemplate.query("""
                select id, title, requested_skill, model_name, scope_payload, status, message_count,
                       last_message_preview, last_message_at, created_at, updated_at
                  from wm_ads_ai_conversation
                 where id = ?
                """, (rs, rowNum) -> mapConversation(rs), id).stream().findFirst();
    }

    public List<ConversationRow> recent(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        return jdbcTemplate.query("""
                select id, title, requested_skill, model_name, scope_payload, status, message_count,
                       last_message_preview, last_message_at, created_at, updated_at
                  from wm_ads_ai_conversation
                 where status = 'ACTIVE'
                 order by coalesce(last_message_at, created_at) desc, id desc
                 limit ?
                """, (rs, rowNum) -> mapConversation(rs), limit);
    }

    public List<MessageRow> messages(long conversationId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return jdbcTemplate.query("""
                select recent.id, recent.conversation_id, recent.role, recent.content_text,
                       recent.content_payload, recent.run_id, recent.status,
                       recent.error_message, recent.created_at
                  from (
                        select id, conversation_id, role, content_text, content_payload,
                               run_id, status, error_message, created_at
                          from wm_ads_ai_conversation_message
                         where conversation_id = ?
                         order by id desc
                         limit ?
                       ) recent
                 order by recent.id asc
                """, (rs, rowNum) -> new MessageRow(
                        rs.getLong("id"),
                        rs.getLong("conversation_id"),
                        rs.getString("role"),
                        rs.getString("content_text"),
                        json(rs.getString("content_payload")),
                        nullableLong(rs.getObject("run_id")),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        timestamp(rs.getTimestamp("created_at"))
                ), conversationId, limit);
    }

    private long insertMessage(
            long conversationId,
            String role,
            String contentText,
            String contentPayload,
            Long runId,
            String status,
            String error
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statement = connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into wm_ads_ai_conversation_message
                        (conversation_id, role, content_text, content_payload, run_id, status, error_message, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, conversationId);
            ps.setString(2, role);
            ps.setString(3, contentText);
            ps.setString(4, contentPayload);
            if (runId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, runId);
            ps.setString(6, status);
            ps.setString(7, error);
            ps.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        };
        jdbcTemplate.update(statement, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法保存 Agent 对话消息");
        }
        return key.longValue();
    }

    private void touchConversation(long id, String preview, String firstTitle) {
        int updated = jdbcTemplate.update("""
                update wm_ads_ai_conversation
                   set title = case when message_count = 0 and ? is not null then ? else title end,
                       message_count = message_count + 1,
                       last_message_preview = ?, last_message_at = ?, updated_at = ?
                 where id = ? and status = 'ACTIVE'
                """, firstTitle, firstTitle, preview, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now()), id);
        if (updated == 0) {
            throw new IllegalArgumentException("Agent 对话不存在或已归档: " + id);
        }
    }

    private ConversationRow mapConversation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConversationRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("requested_skill"),
                rs.getString("model_name"),
                json(rs.getString("scope_payload")),
                rs.getString("status"),
                rs.getInt("message_count"),
                rs.getString("last_message_preview"),
                timestamp(rs.getTimestamp("last_message_at")),
                timestamp(rs.getTimestamp("created_at")),
                timestamp(rs.getTimestamp("updated_at"))
        );
    }

    private JsonNode json(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 对话包含无效 JSON", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法生成 Agent 对话 JSON", exception);
        }
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String title(String question) {
        String compact = question.replaceAll("\\s+", " ").trim();
        return truncate(compact, 48);
    }

    private static String preview(String value) {
        if (!StringUtils.hasText(value)) return null;
        return truncate(value.replaceAll("\\s+", " ").trim(), 240);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    public record ConversationRow(
            long id,
            String title,
            String requestedSkill,
            String model,
            JsonNode scope,
            String status,
            int messageCount,
            String lastMessagePreview,
            LocalDateTime lastMessageAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record MessageRow(
            long id,
            long conversationId,
            String role,
            String content,
            JsonNode payload,
            Long runId,
            String status,
            String error,
            LocalDateTime createdAt
    ) {
    }
}
