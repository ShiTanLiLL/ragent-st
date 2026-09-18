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

import java.io.IOException;
import java.util.List;

/**
 * 把现有 RAG 问答能力暴露为浏览器和其他程序都能调用的 HTTP API。
 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final BailianRagAssistant assistant;
    private final StreamingQuestionService streamingQuestionService;
    private final KnowledgeManagementService knowledgeManagementService;
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
     * @param knowledgeManagementService 保存上传文档和已建立向量索引的知识服务
     */
    public QuestionController(
            BailianRagAssistant assistant,
            StreamingQuestionService streamingQuestionService,
            KnowledgeManagementService knowledgeManagementService
    ) {
        this.assistant = assistant;
        this.streamingQuestionService = streamingQuestionService;
        this.knowledgeManagementService = knowledgeManagementService;
    }

    /**
     * 接收问题 JSON，校验通过后执行完整 RAG，并把内部回答转换成对外 JSON。
     *
     * @param request 已完成 JSON 反序列化并准备接受校验的请求对象
     * @return 包含生成正文与证据来源的 HTTP 响应数据
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间当前线程被中断
     */
    @PostMapping
    public QuestionResponse ask(@Valid @RequestBody QuestionRequest request)
            throws IOException, InterruptedException {
        KnowledgeAnswer answer;
        if (hasUploadedKnowledgeBase(request)) {
            List<EmbeddedKnowledge> indexed = knowledgeManagementService.indexedKnowledge(
                    request.knowledgeBaseId()
            );
            answer = assistant.answerFromIndex(request.question(), indexed);
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
            List<EmbeddedKnowledge> indexed = knowledgeManagementService.indexedKnowledge(
                    request.knowledgeBaseId()
            );
            return streamingQuestionService.startFromIndex(request.question(), indexed);
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
