package com.ai.walmartads.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AdvertisingAgentConversationService {

    private static final String AUTO = "AUTO";
    private static final int HISTORY_MESSAGE_LIMIT = 10;
    private static final int HISTORY_ITEM_CHAR_LIMIT = 6_000;
    private static final int HISTORY_TOTAL_CHAR_LIMIT = 24_000;

    private final AiConversationStore conversationStore;
    private final AiAgentRunStore runStore;
    private final AdvertisingAgentService agentService;

    public AdvertisingAgentConversationService(
            AiConversationStore conversationStore,
            AiAgentRunStore runStore,
            AdvertisingAgentService agentService
    ) {
        this.conversationStore = conversationStore;
        this.runStore = runStore;
        this.agentService = agentService;
    }

    public ConversationDetail create(String skillCode, AdvertisingAgentTools.Scope scope, String model) {
        String normalizedSkill = normalizeSkill(skillCode);
        String normalizedModel = normalizeModel(model);
        validateSkill(normalizedSkill);
        long id = conversationStore.create(normalizedSkill, scope, normalizedModel);
        return detail(id);
    }

    public List<AiConversationStore.ConversationRow> recent(int limit) {
        return conversationStore.recent(limit);
    }

    public ConversationDetail detail(long conversationId) {
        AiConversationStore.ConversationRow conversation = requireConversation(conversationId);
        List<MessageView> messages = conversationStore.messages(conversationId, 100).stream()
                .map(this::messageView)
                .toList();
        return new ConversationDetail(conversation, messages);
    }

    public ConversationDetail send(
            long conversationId,
            String skillCode,
            String question,
            AdvertisingAgentTools.Scope scope,
            String model
    ) {
        return send(conversationId, skillCode, question, scope, model, AdvertisingAgentService.ProgressListener.NOOP);
    }

    public ConversationDetail send(
            long conversationId,
            String skillCode,
            String question,
            AdvertisingAgentTools.Scope scope,
            String model,
            AdvertisingAgentService.ProgressListener progressListener
    ) {
        requireConversation(conversationId);
        String normalizedSkill = normalizeSkill(skillCode);
        String normalizedModel = normalizeModel(model);
        validateSkill(normalizedSkill);
        String normalizedQuestion = normalizeQuestion(question);
        List<AdvertisingAgentService.ConversationTurn> history = conversationHistory(
                conversationStore.messages(conversationId, HISTORY_MESSAGE_LIMIT));

        conversationStore.updateContext(conversationId, normalizedSkill, scope, normalizedModel);
        conversationStore.addUserMessage(conversationId, normalizedQuestion);
        try {
            AdvertisingAgentService.RunRequest request = new AdvertisingAgentService.RunRequest(
                    normalizedSkill, normalizedQuestion, scope, normalizedModel, history, conversationId);
            AiAgentRunStore.RunView run = progressListener == AdvertisingAgentService.ProgressListener.NOOP
                    ? agentService.run(request)
                    : agentService.run(request, progressListener);
            conversationStore.addAssistantMessage(conversationId, run.id(), run.response());
            return detail(conversationId);
        } catch (AdvertisingAgentService.AgentRunException exception) {
            conversationStore.addErrorMessage(conversationId, exception.runId(), rootMessage(exception));
            throw exception;
        } catch (RuntimeException exception) {
            conversationStore.addErrorMessage(conversationId, rootMessage(exception));
            throw exception;
        }
    }

    private MessageView messageView(AiConversationStore.MessageRow row) {
        AiAgentRunStore.RunView run;
        if (row.runId() != null) {
            run = runStore.find(row.runId()).orElse(null);
        } else if ("FAILED".equals(row.status())) {
            run = runStore.findLatestFailed(row.conversationId(), row.createdAt()).orElse(null);
        } else {
            run = null;
        }
        return new MessageView(
                row.id(), row.role(), row.content(), row.payload(), run,
                row.status(), row.error(), row.createdAt());
    }

    private List<AdvertisingAgentService.ConversationTurn> conversationHistory(
            List<AiConversationStore.MessageRow> rows
    ) {
        List<AdvertisingAgentService.ConversationTurn> result = new ArrayList<>();
        int usedChars = 0;
        for (int index = rows.size() - 1; index >= 0; index--) {
            AiConversationStore.MessageRow row = rows.get(index);
            if (!"SUCCESS".equals(row.status())) continue;
            String content = historyContent(row);
            if (!StringUtils.hasText(content)) continue;
            content = truncate(content, HISTORY_ITEM_CHAR_LIMIT);
            if (usedChars + content.length() > HISTORY_TOTAL_CHAR_LIMIT) continue;
            String role = "ASSISTANT".equals(row.role()) ? "ASSISTANT" : "USER";
            result.add(0, new AdvertisingAgentService.ConversationTurn(role, content));
            usedChars += content.length();
        }
        return result;
    }

    private static String historyContent(AiConversationStore.MessageRow row) {
        if ("USER".equals(row.role())) return row.content();
        JsonNode payload = row.payload();
        return payload == null ? row.content() : payload.toString();
    }

    private AiConversationStore.ConversationRow requireConversation(long id) {
        AiConversationStore.ConversationRow conversation = conversationStore.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent 对话不存在: " + id));
        if (!"ACTIVE".equals(conversation.status())) {
            throw new IllegalArgumentException("Agent 对话已归档: " + id);
        }
        return conversation;
    }

    private void validateSkill(String skillCode) {
        if (AUTO.equals(skillCode)) return;
        boolean exists = agentService.skills().stream().anyMatch(skill -> skill.code().equals(skillCode));
        if (!exists) throw new IllegalArgumentException("Skill 不存在或未启用: " + skillCode);
    }

    private static String normalizeSkill(String value) {
        if (!StringUtils.hasText(value) || AUTO.equalsIgnoreCase(value.trim())) return AUTO;
        return value.trim();
    }

    private String normalizeModel(String value) {
        return KieAgentModelCatalog.resolve(value, agentService.defaultModel()).id();
    }

    private static String normalizeQuestion(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("请输入要分析的问题");
        String question = value.trim();
        if (question.length() > 2000) throw new IllegalArgumentException("Agent 问题不能超过 2000 个字符");
        return question;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return StringUtils.hasText(message) ? message : current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    public record ConversationDetail(
            AiConversationStore.ConversationRow conversation,
            List<MessageView> messages
    ) {
    }

    public record MessageView(
            long id,
            String role,
            String content,
            JsonNode payload,
            AiAgentRunStore.RunView run,
            String status,
            String error,
            java.time.LocalDateTime createdAt
    ) {
    }
}
