package com.shitan.ai;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 把现有 RAG 问答能力暴露为浏览器和其他程序都能调用的 HTTP API。
 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final BailianRagAssistant assistant;
    private final StreamingQuestionService streamingQuestionService;
    private final ConversationQuestionService conversationQuestionService;
    private final List<KnowledgeEntry> knowledgeEntries = List.of(
            new KnowledgeEntry(
                    "退货政策",
                    "签收后 7 天内可申请无理由退货。",
                    List.of("退货", "退款")
            ),
            new KnowledgeEntry(
                    "保修政策",
                    "电子产品自购买之日起享受 1 年保修。",
                    List.of("保修", "质保")
            )
    );

    /**
     * 保存 Spring 注入的 RAG 助手，后续每个 HTTP 请求都复用同一个助手对象。
     *
     * @param assistant                已经连接百炼客户端的 RAG 助手
     * @param streamingQuestionService 负责后台生成、SSE 发送和取消状态的流式服务
     * @param conversationQuestionService 负责同步问答的会话记忆和摘要
     */
    public QuestionController(
            BailianRagAssistant assistant,
            StreamingQuestionService streamingQuestionService,
            ConversationQuestionService conversationQuestionService
    ) {
        this.assistant = assistant;
        this.streamingQuestionService = streamingQuestionService;
        this.conversationQuestionService = conversationQuestionService;
    }

    /**
     * 接收问题 JSON，校验通过后执行完整 RAG，并把内部回答转换成对外 JSON。
     *
     * @param request 已完成 JSON 反序列化并准备接受校验的请求对象
     * @return 包含生成正文与证据来源的 HTTP 响应数据
     * @throws Exception 百炼网络通信、响应解析或会话数据库操作失败
     */
    @PostMapping
    public QuestionResponse ask(@Valid @RequestBody QuestionRequest request)
            throws Exception {
        if (request.conversationId() != null && !request.conversationId().isBlank()
                && (request.userId() == null || request.userId().isBlank())) {
            throw new IllegalArgumentException("conversationId 必须和 userId 一起提供");
        }
        if (request.userId() != null && !request.userId().isBlank()) {
            if (!hasUploadedKnowledgeBase(request)) {
                throw new IllegalArgumentException("带会话记忆的问答必须指定 knowledgeBaseId");
            }
            return conversationQuestionService.ask(request);
        }
        KnowledgeAnswer answer;
        if (hasUploadedKnowledgeBase(request)) {
            answer = assistant.answerFromDatabase(
                    request.question(),
                    request.knowledgeBaseId()
            );
        } else {
            answer = assistant.answer(request.question(), knowledgeEntries);
        }
        return new QuestionResponse(answer.content(), answer.sourceTitle());
    }

    /**
     * 创建一次流式问答，立即返回 SSE 连接，后续由后台线程依次发送 meta、message 和 done。
     *
     * @param request 已通过非空校验的用户问题
     * @return 保持打开、可以逐条向调用方发送事件的 SSE 连接
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody QuestionRequest request) {
        if (hasUploadedKnowledgeBase(request)) {
            return streamingQuestionService.startFromDatabase(
                    request.question(),
                    request.knowledgeBaseId()
            );
        }
        return streamingQuestionService.start(request.question(), knowledgeEntries);
    }

    /**
     * 根据 meta 事件中的 taskId 取消仍在运行的流式问答。
     *
     * @param taskId 要取消的任务编号
     * @return 找到运行任务时返回 204，任务已经结束或不存在时返回 404
     */
    @DeleteMapping("/stream/{taskId}")
    public ResponseEntity<Void> cancel(@PathVariable("taskId") String taskId) {
        if (streamingQuestionService.cancel(taskId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 判断本次请求是查询运营人员上传的知识库，还是沿用前几课的内置政策。
     *
     * @param request 已完成 JSON 反序列化的问答请求
     * @return knowledgeBaseId 存在且不是空白文字时返回 true
     */
    private boolean hasUploadedKnowledgeBase(QuestionRequest request) {
        return request.knowledgeBaseId() != null && !request.knowledgeBaseId().isBlank();
    }
}
