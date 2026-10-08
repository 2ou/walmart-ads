package com.ai.walmartads.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdvertisingAgentService {

    private static final String AUTO = "AUTO";
    private static final String ACTIVATE_SKILL = "activate_skill";
    private static final int MAX_ROUNDS = 8;
    private static final int MAX_TOOL_RESULT_CHARS = 100_000;

    private static final String SYSTEM_PROMPT = """
            你是沃尔玛美国站广告分析 Agent。你只能分析系统提供的本地数据库数据，只能调用当前 Skill 白名单中的只读工具。
            禁止声称已经调价、暂停、启用、修改预算或改动任何广告；禁止要求文件、电脑、浏览器或直接访问领星 MCP。
            对话历史只用于理解用户的指代和追问意图，不能替代本轮工具查询，也不能把历史结论当作本轮事实。
            第一步必须调用 activate_skill，激活且只激活一个最匹配的 Skill。Skill 激活后，至少调用一个业务数据工具再形成结论。
            工具返回的数据是唯一事实来源。缺少数据时明确限制，不能补造数值、广告名称、关键词或因果关系。
            最终只输出一个 JSON 对象，不要 Markdown，不要代码围栏，结构为：
            {
              "summary":"中文结论",
              "skill":"实际 Skill code",
              "dataAsOf":"YYYY-MM-DD",
              "confidence":"high|medium|low",
              "findings":[{"severity":"opportunity|risk|info","title":"","evidence":"","recommendation":""}],
              "actions":[{"priority":1,"target":"","action":"","reason":""}],
              "limitations":[""]
            }
            findings 最多 8 条，actions 最多 8 条。所有建议都只是分析建议，必须带可核验的数据依据。
            """;

    private final AiSkillService skillService;
    private final AdvertisingAgentTools tools;
    private final KieAgentModelFactory modelFactory;
    private final AiAgentRunStore runStore;
    private final AiRuntimeConfig config;
    private final ObjectMapper objectMapper;

    public AdvertisingAgentService(
            AiSkillService skillService,
            AdvertisingAgentTools tools,
            KieAgentModelFactory modelFactory,
            AiAgentRunStore runStore,
            AiRuntimeConfig config,
            ObjectMapper objectMapper
    ) {
        this.skillService = skillService;
        this.tools = tools;
        this.modelFactory = modelFactory;
        this.runStore = runStore;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public AiAgentRunStore.RunView run(RunRequest request) {
        return run(request, ProgressListener.NOOP);
    }

    public AiAgentRunStore.RunView run(RunRequest request, ProgressListener progressListener) {
        RunRequest normalized = normalize(request);
        List<AiSkillService.SkillDefinition> availableSkills = skillService.enabledSkills();
        if (availableSkills.isEmpty()) {
            throw new IllegalArgumentException("没有已启用的数据库 Skill");
        }
        String requestedSkill = normalized.skillCode();
        if (!AUTO.equals(requestedSkill)) {
            skillService.enabledSkill(requestedSkill)
                    .orElseThrow(() -> new IllegalArgumentException("Skill 不存在或未启用: " + requestedSkill));
        }
        String scopeJson = writeJson(normalized.scope());
        long runId = normalized.conversationId() == null
                ? runStore.create(requestedSkill, normalized.question(), scopeJson, normalized.model())
                : runStore.create(requestedSkill, normalized.question(), scopeJson, normalized.model(), normalized.conversationId());
        ExecutionTrace trace = new ExecutionTrace(runId, progressListener);
        AiSkillService.SkillDefinition activated = null;
        try {
            trace.succeed("scope", "SCOPE", "锁定分析范围", scopeSummary(normalized.scope()),
                    scopeDetails(normalized), 0L);
            ChatModel model = modelFactory.create(normalized.model());
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYSTEM_PROMPT + "\n\n可用 Skill：\n" + skillCatalog(availableSkills)));
            appendConversationHistory(messages, normalized.history());
            messages.add(UserMessage.from(userPrompt(normalized)));
            List<ToolSpecification> specifications = new ArrayList<>();
            specifications.add(activationSpecification());
            Set<String> allowedTools = new LinkedHashSet<>();
            boolean businessToolExecuted = false;
            TokenUsage totalUsage = new TokenUsage(0, 0, 0);
            String finalText = null;
            String responseModel = normalized.model();

            for (int round = 1; round <= MAX_ROUNDS; round++) {
                String modelStepId = "model-" + round;
                String modelStepTitle = round == 1 ? "选择分析 Skill" : "分析数据并规划下一步";
                ObjectNode modelDetails = objectMapper.createObjectNode();
                modelDetails.put("model", agentModelName(normalized.model()));
                modelDetails.put("round", round);
                trace.start(modelStepId, "MODEL", modelStepTitle,
                        round == 1 ? "正在判断问题应使用哪个数据库 Skill" : "正在根据已查询数据判断是否需要继续调用工具",
                        modelDetails);
                long modelStarted = System.nanoTime();
                ChatResponse response = chatWithRetry(model, ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(specifications)
                        .build());
                long modelDuration = elapsedMillis(modelStarted);
                if (response.tokenUsage() != null) {
                    totalUsage = totalUsage.add(response.tokenUsage());
                    modelDetails.put("inputTokens", value(response.tokenUsage().inputTokenCount()));
                    modelDetails.put("outputTokens", value(response.tokenUsage().outputTokenCount()));
                }
                if (StringUtils.hasText(response.modelName())) {
                    responseModel = response.modelName();
                }
                messages.add(response.aiMessage());
                trace.succeed(modelStepId, "MODEL", modelStepTitle, modelDecisionSummary(response.aiMessage()),
                        modelDetails, modelDuration);

                if (!response.aiMessage().hasToolExecutionRequests()) {
                    if (activated == null) {
                        throw new IllegalArgumentException("Agent 未激活数据库 Skill");
                    }
                    if (!businessToolExecuted) {
                        throw new IllegalArgumentException("Agent 未查询业务数据，已拒绝生成无依据结论");
                    }
                    finalText = response.aiMessage().text();
                    break;
                }

                int toolIndex = 0;
                for (ToolExecutionRequest toolRequest : response.aiMessage().toolExecutionRequests()) {
                    toolIndex++;
                    String result;
                    if (ACTIVATE_SKILL.equals(toolRequest.name())) {
                        if (activated != null) {
                            throw new IllegalArgumentException("一次运行只能激活一个 Skill");
                        }
                        activated = activate(toolRequest.arguments(), requestedSkill);
                        allowedTools.addAll(activated.tools());
                        specifications.clear();
                        specifications.addAll(activated.tools().stream().map(tools::specification).toList());
                        result = activationResult(activated, normalized.scope());
                        ObjectNode skillDetails = objectMapper.createObjectNode();
                        skillDetails.put("skillCode", activated.code());
                        skillDetails.put("skillName", activated.name());
                        skillDetails.put("skillVersion", activated.version());
                        String reason = activationReason(toolRequest.arguments());
                        if (StringUtils.hasText(reason)) skillDetails.put("reason", reason);
                        trace.succeed("skill", "SKILL", "启用 " + activated.name(),
                                StringUtils.hasText(reason) ? reason : "已按问题类型启用只读分析 Skill",
                                skillDetails, 0L);
                    } else {
                        if (activated == null || !allowedTools.contains(toolRequest.name())) {
                            throw new IllegalArgumentException("Skill 未授权工具调用: " + toolRequest.name());
                        }
                        String toolStepId = "tool-" + round + "-" + toolIndex;
                        String toolTitle = toolDisplayName(toolRequest.name());
                        ObjectNode queryDetails = toolQueryDetails(toolRequest.name(), normalized.scope());
                        trace.start(toolStepId, "TOOL", toolTitle, "正在查询本地数据库", queryDetails);
                        long toolStarted = System.nanoTime();
                        try {
                            result = tools.execute(toolRequest.name(), toolRequest.arguments(), normalized.scope());
                        } catch (RuntimeException exception) {
                            trace.fail(toolStepId, "TOOL", toolTitle, safeProgressError(exception),
                                    queryDetails, elapsedMillis(toolStarted));
                            throw exception;
                        }
                        businessToolExecuted = true;
                        if (result.length() > MAX_TOOL_RESULT_CHARS) {
                            trace.fail(toolStepId, "TOOL", toolTitle,
                                    "工具结果过大，请缩小款号或关键词范围", queryDetails,
                                    elapsedMillis(toolStarted));
                            throw new IllegalArgumentException("工具结果过大，请缩小款号或关键词范围");
                        }
                        ToolResultSummary resultSummary = summarizeToolResult(
                                toolRequest.name(), result, normalized.scope(), queryDetails);
                        trace.succeed(toolStepId, "TOOL", toolTitle, resultSummary.summary(),
                                resultSummary.details(), elapsedMillis(toolStarted));
                    }
                    messages.add(ToolExecutionResultMessage.from(toolRequest, result));
                }
            }

            if (!StringUtils.hasText(finalText)) {
                throw new IllegalArgumentException("Agent 超过最大工具调用轮次，未能形成结论");
            }
            trace.start("result", "RESULT", "整理分析结论", "正在校验结构化结果和数据依据",
                    objectMapper.createObjectNode());
            long resultStarted = System.nanoTime();
            JsonNode responsePayload = normalizedResponse(finalText, activated, normalized.scope());
            ObjectNode resultDetails = objectMapper.createObjectNode();
            resultDetails.put("findingCount", responsePayload.path("findings").size());
            resultDetails.put("actionCount", responsePayload.path("actions").size());
            resultDetails.put("confidence", responsePayload.path("confidence").asText("medium"));
            trace.succeed("result", "RESULT", "整理分析结论",
                    "已生成可核验的发现与建议", resultDetails, elapsedMillis(resultStarted));
            int promptTokens = value(totalUsage.inputTokenCount());
            int completionTokens = value(totalUsage.outputTokenCount());
            int totalTokens = value(totalUsage.totalTokenCount());
            trace.succeed("audit", "AUDIT", "保存运行审计", "执行步骤、工具摘要和最终结论已保存",
                    objectMapper.createObjectNode().put("totalTokens", totalTokens), 0L);
            runStore.succeed(runId, activated, trace.json(), writeJson(responsePayload), responseModel,
                    promptTokens, completionTokens, totalTokens);
            return runStore.find(runId).orElseThrow(() -> new IllegalStateException("Agent 运行结果保存后无法读取"));
        } catch (RuntimeException exception) {
            String error = rootMessage(exception);
            trace.failActive(error);
            if (!trace.hasFailedStep()) {
                trace.fail("failed", "ERROR", "执行失败", safeProgressError(exception),
                        objectMapper.createObjectNode(), null);
            }
            runStore.fail(runId, activated == null ? null : activated.code(), trace.json(), error);
            throw new AgentRunException(runId, error, exception);
        }
    }

    public List<AiSkillService.SkillDefinition> skills() {
        return skillService.enabledSkills();
    }

    public List<KieAgentModelCatalog.ModelDefinition> models() {
        return KieAgentModelCatalog.models();
    }

    public String defaultModel() {
        return KieAgentModelCatalog.resolve(null, config.getModel()).id();
    }

    public java.util.Optional<AiAgentRunStore.RunView> latest() {
        return runStore.latestSuccessful();
    }

    public List<AiAgentRunStore.RunView> recent(int limit) {
        return runStore.recent(limit);
    }

    private AiSkillService.SkillDefinition activate(String arguments, String requestedSkill) {
        JsonNode args;
        try {
            args = objectMapper.readTree(arguments == null ? "{}" : arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("activate_skill 参数不是有效 JSON", exception);
        }
        String selected = args.path("skillCode").asText("").trim();
        if (!StringUtils.hasText(selected)) {
            throw new IllegalArgumentException("activate_skill 缺少 skillCode");
        }
        if (!AUTO.equals(requestedSkill) && !requestedSkill.equals(selected)) {
            throw new IllegalArgumentException("用户指定 Skill 为 " + requestedSkill + "，模型不得改选 " + selected);
        }
        return skillService.enabledSkill(selected)
                .orElseThrow(() -> new IllegalArgumentException("模型选择了不存在或未启用的 Skill: " + selected));
    }

    private ToolSpecification activationSpecification() {
        return ToolSpecification.builder()
                .name(ACTIVATE_SKILL)
                .description("在读取任何广告数据前激活一个数据库 Skill；每次运行只能调用一次。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("skillCode", "必须从系统给出的 Skill code 中选择")
                        .addStringProperty("reason", "选择这个 Skill 的简短原因")
                        .required("skillCode")
                        .additionalProperties(false)
                        .build())
                .strict(false)
                .build();
    }

    private String activationResult(AiSkillService.SkillDefinition skill, AdvertisingAgentTools.Scope scope) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("activated", true);
        result.put("skillCode", skill.code());
        result.put("skillName", skill.name());
        result.put("version", skill.version());
        result.put("instructions", skill.instructions());
        result.set("allowedTools", objectMapper.valueToTree(skill.tools()));
        result.set("lockedScope", objectMapper.valueToTree(scope));
        if (StringUtils.hasText(skill.outputSchema())) {
            try {
                result.set("outputSchema", objectMapper.readTree(skill.outputSchema()));
            } catch (JsonProcessingException exception) {
                result.put("outputSchema", skill.outputSchema());
            }
        }
        return writeJson(result);
    }

    private String skillCatalog(List<AiSkillService.SkillDefinition> skills) {
        ArrayNode result = objectMapper.createArrayNode();
        for (AiSkillService.SkillDefinition skill : skills) {
            ObjectNode item = result.addObject();
            item.put("skillCode", skill.code());
            item.put("name", skill.name());
            item.put("description", skill.description());
        }
        return writeJson(result);
    }

    private String userPrompt(RunRequest request) {
        String forced = AUTO.equals(request.skillCode())
                ? "由你从可用 Skill 中选择最匹配的一项"
                : "必须激活用户指定 Skill：" + request.skillCode();
        return """
                用户问题：%s
                固定分析范围：%s 至 %s
                固定款号：%s
                固定广告类型：%s
                Skill 选择要求：%s
                日期、款号和广告类型是服务端锁定范围；即使工具参数不同，也必须以固定范围为准。
                """.formatted(
                request.question(), request.scope().startDate(), request.scope().endDate(),
                valueOrAll(request.scope().parentSku()), valueOrAll(request.scope().adType()), forced);
    }

    private static void appendConversationHistory(
            List<ChatMessage> messages,
            List<ConversationTurn> history
    ) {
        if (history == null || history.isEmpty()) return;
        for (ConversationTurn turn : history) {
            if (turn == null || !StringUtils.hasText(turn.content())) continue;
            if ("ASSISTANT".equalsIgnoreCase(turn.role())) {
                messages.add(AiMessage.from(turn.content()));
            } else {
                messages.add(UserMessage.from(turn.content()));
            }
        }
    }

    private ObjectNode scopeDetails(RunRequest request) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("model", agentModelName(request.model()));
        details.set("scope", objectMapper.valueToTree(request.scope()));
        return details;
    }

    private static String scopeSummary(AdvertisingAgentTools.Scope scope) {
        String sku = StringUtils.hasText(scope.parentSku()) ? scope.parentSku() : "全部款号";
        String adType = StringUtils.hasText(scope.adType()) && !"ALL".equals(scope.adType())
                ? scope.adType() + " 广告" : "全部广告";
        return "%s 至 %s · %s · %s".formatted(scope.startDate(), scope.endDate(), sku, adType);
    }

    private String agentModelName(String modelId) {
        return KieAgentModelCatalog.resolve(modelId, config.getModel()).name();
    }

    private String modelDecisionSummary(AiMessage message) {
        if (!message.hasToolExecutionRequests()) {
            return "已根据工具结果生成结构化分析结论";
        }
        List<String> decisions = message.toolExecutionRequests().stream()
                .map(ToolExecutionRequest::name)
                .map(name -> ACTIVATE_SKILL.equals(name) ? "选择数据库 Skill" : "调用“" + toolDisplayName(name) + "”")
                .distinct()
                .toList();
        return String.join("，", decisions);
    }

    private String activationReason(String arguments) {
        try {
            return objectMapper.readTree(StringUtils.hasText(arguments) ? arguments : "{}")
                    .path("reason").asText("").trim();
        } catch (JsonProcessingException ignored) {
            return "";
        }
    }

    private static String toolDisplayName(String toolCode) {
        return switch (toolCode) {
            case AdvertisingAgentTools.OVERVIEW -> "查询广告经营总览";
            case AdvertisingAgentTools.DIAGNOSIS -> "查询款号广告诊断";
            case AdvertisingAgentTools.KEYWORDS -> "查询手动广告关键词";
            case AdvertisingAgentTools.HISTORY -> "查询历史广告表现";
            case AdvertisingAgentTools.FRESHNESS -> "检查领星数据新鲜度";
            default -> toolCode;
        };
    }

    private ObjectNode toolQueryDetails(String toolCode, AdvertisingAgentTools.Scope scope) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("toolCode", toolCode);
        details.put("toolName", toolDisplayName(toolCode));
        details.set("scope", objectMapper.valueToTree(scope));
        return details;
    }

    private ToolResultSummary summarizeToolResult(
            String toolCode,
            String result,
            AdvertisingAgentTools.Scope scope,
            ObjectNode queryDetails
    ) {
        ObjectNode details = queryDetails.deepCopy();
        details.put("resultChars", result.length());
        JsonNode root;
        try {
            root = objectMapper.readTree(result);
        } catch (JsonProcessingException exception) {
            return new ToolResultSummary("数据库查询完成，结果已交给模型分析", details);
        }
        return switch (toolCode) {
            case AdvertisingAgentTools.KEYWORDS -> {
                int returned = root.path("returnedKeywordCount").asInt(0);
                int total = root.path("filteredKeywordCount").asInt(returned);
                details.put("returnedCount", returned);
                details.put("totalCount", total);
                details.set("metrics", compactMetrics(root.path("filteredSummary"),
                        List.of("impressions", "clicks", "spend", "sales", "orders", "roas", "ctr", "cvr")));
                yield new ToolResultSummary("命中 %d 个关键词，本次返回 %d 个".formatted(total, returned), details);
            }
            case AdvertisingAgentTools.DIAGNOSIS -> {
                JsonNode style = root.path("style");
                if (style.isObject()) {
                    int campaigns = style.path("campaignCount").asInt(0);
                    details.put("returnedCount", campaigns);
                    details.set("metrics", compactMetrics(style,
                            List.of("spend", "adSales", "adOrders", "clicks", "historicalCampaignCount")));
                    yield new ToolResultSummary("返回 %d 个广告活动的诊断摘要".formatted(campaigns), details);
                }
                int returned = root.path("returnedStyleCount").asInt(0);
                int total = root.path("totalStyleCount").asInt(returned);
                details.put("returnedCount", returned);
                details.put("totalCount", total);
                yield new ToolResultSummary("命中 %d 个款号，本次返回 %d 个".formatted(total, returned), details);
            }
            case AdvertisingAgentTools.HISTORY -> {
                int returned = root.path("returnedCampaignCount").asInt(0);
                int total = root.path("totalCampaignCount").asInt(returned);
                details.put("returnedCount", returned);
                details.put("totalCount", total);
                yield new ToolResultSummary("命中 %d 个历史广告，本次返回 %d 个".formatted(total, returned), details);
            }
            case AdvertisingAgentTools.FRESHNESS -> {
                int levels = root.path("reportLevels").size();
                int syncRuns = root.path("recentSyncRuns").size();
                details.put("reportLevelCount", levels);
                details.put("syncRunCount", syncRuns);
                yield new ToolResultSummary("读取 %d 个报表层级和 %d 次最近同步记录".formatted(levels, syncRuns), details);
            }
            case AdvertisingAgentTools.OVERVIEW -> new ToolResultSummary(
                    "已读取 %s 至 %s 的广告经营指标".formatted(scope.startDate(), scope.endDate()), details);
            default -> new ToolResultSummary("数据库查询完成，结果已交给模型分析", details);
        };
    }

    private ObjectNode compactMetrics(JsonNode source, List<String> fields) {
        ObjectNode metrics = objectMapper.createObjectNode();
        if (!source.isObject()) return metrics;
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null && value.isNumber()) metrics.set(field, value);
        }
        return metrics;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String safeProgressError(Throwable throwable) {
        String message = rootMessage(throwable);
        if (!StringUtils.hasText(message)) return "执行失败";
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) : compact;
    }

    private JsonNode normalizedResponse(
            String text,
            AiSkillService.SkillDefinition activated,
            AdvertisingAgentTools.Scope scope
    ) {
        JsonNode parsed = parseJsonText(text);
        ObjectNode result;
        if (parsed != null && parsed.isObject()) {
            result = (ObjectNode) parsed;
        } else {
            result = objectMapper.createObjectNode();
            result.put("summary", StringUtils.hasText(text) ? text.trim() : "Agent 未返回有效结论");
        }
        if (!result.hasNonNull("summary")) result.put("summary", "Agent 分析完成");
        result.put("skill", activated.code());
        result.put("dataAsOf", scope.endDate().toString());
        if (!result.hasNonNull("confidence")) result.put("confidence", "medium");
        if (!result.path("findings").isArray()) result.set("findings", objectMapper.createArrayNode());
        if (!result.path("actions").isArray()) result.set("actions", objectMapper.createArrayNode());
        if (!result.path("limitations").isArray()) result.set("limitations", objectMapper.createArrayNode());
        return result;
    }

    private JsonNode parseJsonText(String text) {
        if (!StringUtils.hasText(text)) return null;
        String cleaned = stripCodeFence(text);
        try {
            return objectMapper.readTree(cleaned);
        } catch (JsonProcessingException ignored) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(cleaned.substring(start, end + 1));
                } catch (JsonProcessingException ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }

    private static ChatResponse chatWithRetry(ChatModel model, ChatRequest request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return model.chat(request);
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt == 3 || !retryable(exception)) {
                    throw exception;
                }
                try {
                    Thread.sleep(attempt * 500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalArgumentException("Agent 调用被中断", interrupted);
                }
            }
        }
        throw last;
    }

    private static boolean retryable(Throwable exception) {
        String message = rootMessage(exception).toLowerCase(Locale.ROOT);
        return message.contains("429") || message.contains("500") || message.contains("502")
                || message.contains("503") || message.contains("timeout") || message.contains("timed out");
    }

    private RunRequest normalize(RunRequest request) {
        if (request == null || request.scope() == null) {
            throw new IllegalArgumentException("缺少 Agent 分析范围");
        }
        String skillCode = StringUtils.hasText(request.skillCode())
                ? request.skillCode().trim()
                : AUTO;
        if (AUTO.equalsIgnoreCase(skillCode)) skillCode = AUTO;
        String question = StringUtils.hasText(request.question())
                ? request.question().trim()
                : "请分析所选范围的广告表现，指出主要机会、风险和下一步建议。";
        if (question.length() > 2000) {
            throw new IllegalArgumentException("Agent 问题不能超过 2000 个字符");
        }
        List<ConversationTurn> history = request.history() == null
                ? List.of()
                : request.history().stream()
                        .filter(turn -> turn != null && StringUtils.hasText(turn.content()))
                        .limit(10)
                        .toList();
        String model = KieAgentModelCatalog.resolve(request.model(), config.getModel()).id();
        return new RunRequest(skillCode, question, request.scope(), model, history, request.conversationId());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法生成 Agent JSON", exception);
        }
    }

    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        return firstLineEnd >= 0 && closingFence > firstLineEnd
                ? trimmed.substring(firstLineEnd + 1, closingFence).trim()
                : trimmed;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String valueOrAll(String value) {
        return StringUtils.hasText(value) ? value : "全部";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private final class ExecutionTrace {
        private final long runId;
        private final ProgressListener listener;
        private final List<ProgressEvent> steps = new ArrayList<>();
        private final Map<String, Long> startedNanos = new HashMap<>();
        private String activeStepId;

        private ExecutionTrace(long runId, ProgressListener listener) {
            this.runId = runId;
            this.listener = listener == null ? ProgressListener.NOOP : listener;
        }

        private void start(String stepId, String kind, String title, String summary, JsonNode details) {
            activeStepId = stepId;
            startedNanos.put(stepId, System.nanoTime());
            upsert(new ProgressEvent(runId, stepId, kind, "RUNNING", title, summary,
                    details, null, LocalDateTime.now()));
        }

        private void succeed(
                String stepId,
                String kind,
                String title,
                String summary,
                JsonNode details,
                Long durationMs
        ) {
            if (stepId.equals(activeStepId)) activeStepId = null;
            startedNanos.remove(stepId);
            upsert(new ProgressEvent(runId, stepId, kind, "SUCCESS", title, summary,
                    details, durationMs, LocalDateTime.now()));
        }

        private void fail(
                String stepId,
                String kind,
                String title,
                String summary,
                JsonNode details,
                Long durationMs
        ) {
            if (stepId.equals(activeStepId)) activeStepId = null;
            startedNanos.remove(stepId);
            upsert(new ProgressEvent(runId, stepId, kind, "FAILED", title, summary,
                    details, durationMs, LocalDateTime.now()));
        }

        private void failActive(String error) {
            if (!StringUtils.hasText(activeStepId)) return;
            ProgressEvent active = find(activeStepId);
            if (active == null) return;
            Long started = startedNanos.get(activeStepId);
            Long duration = started == null ? null : elapsedMillis(started);
            fail(active.stepId(), active.kind(), active.title(), safeProgressError(new RuntimeException(error)),
                    active.details(), duration);
        }

        private boolean hasFailedStep() {
            return steps.stream().anyMatch(step -> "FAILED".equals(step.status()));
        }

        private ProgressEvent find(String stepId) {
            return steps.stream().filter(step -> step.stepId().equals(stepId)).findFirst().orElse(null);
        }

        private void upsert(ProgressEvent event) {
            int existing = -1;
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).stepId().equals(event.stepId())) {
                    existing = index;
                    break;
                }
            }
            if (existing >= 0) steps.set(existing, event); else steps.add(event);
            runStore.updateTrace(runId, json());
            try {
                listener.onProgress(event);
            } catch (RuntimeException ignored) {
                // 客户端中断流式读取时，运行和数据库审计仍应继续完成。
            }
        }

        private String json() {
            return writeJson(steps);
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NOOP = event -> { };

        void onProgress(ProgressEvent event);
    }

    public record ProgressEvent(
            long runId,
            String stepId,
            String kind,
            String status,
            String title,
            String summary,
            JsonNode details,
            Long durationMs,
            LocalDateTime timestamp
    ) {
    }

    public static final class AgentRunException extends RuntimeException {
        private final long runId;

        public AgentRunException(long runId, String message, Throwable cause) {
            super(message, cause);
            this.runId = runId;
        }

        public long runId() {
            return runId;
        }
    }

    private record ToolResultSummary(String summary, ObjectNode details) {
    }

    public record ConversationTurn(String role, String content) {
    }

    public record RunRequest(
            String skillCode,
            String question,
            AdvertisingAgentTools.Scope scope,
            String model,
            List<ConversationTurn> history,
            Long conversationId
    ) {
        public RunRequest(String skillCode, String question, AdvertisingAgentTools.Scope scope) {
            this(skillCode, question, scope, null, List.of(), null);
        }

        public static RunRequest of(
                String skillCode,
                String question,
                LocalDate startDate,
                LocalDate endDate,
                String parentSku,
                String adType,
                String model
        ) {
            return new RunRequest(skillCode, question,
                    new AdvertisingAgentTools.Scope(startDate, endDate, parentSku, adType),
                    model, List.of(), null);
        }
    }
}
