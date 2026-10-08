package com.ai.walmartads.ai;

import com.ai.walmartads.shared.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/ai/agent")
public class AdvertisingAgentController {

    private final AdvertisingAgentService service;
    private final AdvertisingAgentConversationService conversationService;
    private final ObjectMapper objectMapper;

    public AdvertisingAgentController(
            AdvertisingAgentService service,
            AdvertisingAgentConversationService conversationService,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/skills")
    public ApiResponse<List<SkillView>> skills() {
        return ApiResponse.ok(service.skills().stream().map(SkillView::from).toList());
    }

    @GetMapping("/models")
    public ApiResponse<ModelCatalogView> models() {
        return ApiResponse.ok(new ModelCatalogView(
                service.defaultModel(),
                service.models().stream().map(ModelView::from).toList()
        ));
    }

    @GetMapping("/runs/latest")
    public ApiResponse<AiAgentRunStore.RunView> latest() {
        return service.latest()
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.ok("暂无 Agent 分析记录", null));
    }

    @GetMapping("/runs")
    public ApiResponse<List<AiAgentRunStore.RunView>> recent(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(service.recent(limit));
    }

    @PostMapping("/runs")
    public ApiResponse<AiAgentRunStore.RunView> run(@RequestBody RunBody body) {
        if (body == null) {
            throw new IllegalArgumentException("缺少 Agent 请求");
        }
        return ApiResponse.ok("广告分析 Agent 已完成", service.run(AdvertisingAgentService.RunRequest.of(
                body.skillCode(), body.question(), body.startDate(), body.endDate(), body.parentSku(), body.adType(),
                body.model()
        )));
    }

    @GetMapping("/conversations")
    public ApiResponse<List<AiConversationStore.ConversationRow>> conversations(
            @RequestParam(defaultValue = "30") int limit
    ) {
        return ApiResponse.ok(conversationService.recent(limit));
    }

    @PostMapping("/conversations")
    public ApiResponse<AdvertisingAgentConversationService.ConversationDetail> createConversation(
            @RequestBody ConversationBody body
    ) {
        if (body == null) throw new IllegalArgumentException("缺少 Agent 对话请求");
        return ApiResponse.ok("Agent 对话已创建", conversationService.create(
                body.skillCode(), scope(body.startDate(), body.endDate(), body.parentSku(), body.adType()), body.model()
        ));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<AdvertisingAgentConversationService.ConversationDetail> conversation(
            @PathVariable long conversationId
    ) {
        return ApiResponse.ok(conversationService.detail(conversationId));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<AdvertisingAgentConversationService.ConversationDetail> sendMessage(
            @PathVariable long conversationId,
            @RequestBody MessageBody body
    ) {
        if (body == null) throw new IllegalArgumentException("缺少 Agent 消息请求");
        return ApiResponse.ok("Agent 已回复", conversationService.send(
                conversationId,
                body.skillCode(),
                body.question(),
                scope(body.startDate(), body.endDate(), body.parentSku(), body.adType()),
                body.model()
        ));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/messages/stream",
            produces = "application/x-ndjson"
    )
    public ResponseEntity<StreamingResponseBody> sendMessageStream(
            @PathVariable long conversationId,
            @RequestBody MessageBody body
    ) {
        if (body == null) throw new IllegalArgumentException("缺少 Agent 消息请求");
        AdvertisingAgentTools.Scope lockedScope = scope(
                body.startDate(), body.endDate(), body.parentSku(), body.adType());
        StreamingResponseBody stream = output -> {
            AtomicBoolean connected = new AtomicBoolean(true);
            try {
                AdvertisingAgentConversationService.ConversationDetail detail = conversationService.send(
                        conversationId,
                        body.skillCode(),
                        body.question(),
                        lockedScope,
                        body.model(),
                        progress -> writeStreamEvent(output, StreamEvent.progress(progress), connected)
                );
                writeStreamEvent(output, StreamEvent.complete(detail), connected);
            } catch (RuntimeException exception) {
                AdvertisingAgentConversationService.ConversationDetail detail = null;
                try {
                    detail = conversationService.detail(conversationId);
                } catch (RuntimeException ignored) {
                    // 对话本身不可读时仍返回原始错误。
                }
                writeStreamEvent(output, StreamEvent.error(rootMessage(exception), detail), connected);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    private void writeStreamEvent(OutputStream output, StreamEvent event, AtomicBoolean connected) {
        if (!connected.get()) return;
        try {
            byte[] data = objectMapper.writeValueAsBytes(event);
            synchronized (output) {
                output.write(data);
                output.write('\n');
                output.flush();
            }
        } catch (IOException exception) {
            connected.set(false);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static AdvertisingAgentTools.Scope scope(
            LocalDate startDate,
            LocalDate endDate,
            String parentSku,
            String adType
    ) {
        return new AdvertisingAgentTools.Scope(startDate, endDate, parentSku, adType);
    }

    public record RunBody(
            String skillCode,
            String question,
            LocalDate startDate,
            LocalDate endDate,
            String parentSku,
            String adType,
            String model
    ) {
    }

    public record ConversationBody(
            String skillCode,
            LocalDate startDate,
            LocalDate endDate,
            String parentSku,
            String adType,
            String model
    ) {
    }

    public record MessageBody(
            String skillCode,
            String question,
            LocalDate startDate,
            LocalDate endDate,
            String parentSku,
            String adType,
            String model
    ) {
    }

    public record StreamEvent(
            String type,
            AdvertisingAgentService.ProgressEvent progress,
            AdvertisingAgentConversationService.ConversationDetail detail,
            String message
    ) {
        static StreamEvent progress(AdvertisingAgentService.ProgressEvent progress) {
            return new StreamEvent("progress", progress, null, null);
        }

        static StreamEvent complete(AdvertisingAgentConversationService.ConversationDetail detail) {
            return new StreamEvent("complete", null, detail, null);
        }

        static StreamEvent error(
                String message,
                AdvertisingAgentConversationService.ConversationDetail detail
        ) {
            return new StreamEvent("error", null, detail, message);
        }
    }

    public record ModelCatalogView(String defaultModel, List<ModelView> models) {
    }

    public record ModelView(
            String id,
            String name,
            String family,
            boolean recommended,
            String description
    ) {
        static ModelView from(KieAgentModelCatalog.ModelDefinition model) {
            return new ModelView(model.id(), model.name(), model.family(), model.recommended(), model.description());
        }
    }

    public record SkillView(
            String code,
            String name,
            String description,
            int version,
            List<String> tools
    ) {
        static SkillView from(AiSkillService.SkillDefinition skill) {
            return new SkillView(skill.code(), skill.name(), skill.description(), skill.version(), skill.tools());
        }
    }
}
