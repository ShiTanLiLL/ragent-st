package com.shitan.ai;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供问答追踪查询和回答反馈入口；所有操作都要求 userId 匹配 Run 所有者。
 */
@RestController
@RequestMapping("/api/rag-runs")
public class RagTraceController {

    private final RagTraceService traceService;

    /**
     * 保存追踪业务服务，Controller 不直接执行 SQL。
     */
    public RagTraceController(RagTraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * 根据响应中的 runId 查询整次运行和顺序节点。
     */
    @GetMapping("/{runId}")
    public RagTraceDetail detail(
            @PathVariable("runId") String runId,
            @RequestParam("userId") String userId
    ) {
        return traceService.detail(runId, userId);
    }

    /**
     * 查询一个会话最近的追踪；失败响应没有 runId 时可使用这个入口定位。
     */
    @GetMapping("/latest")
    public RagTraceDetail latest(
            @RequestParam("conversationId") String conversationId,
            @RequestParam("userId") String userId
    ) {
        return traceService.latest(conversationId, userId);
    }

    /**
     * 对一次成功回答提交或修改赞踩和原因。
     */
    @PostMapping("/{runId}/feedback")
    public AnswerFeedback feedback(
            @PathVariable("runId") String runId,
            @Valid @RequestBody FeedbackRequest request
    ) {
        return traceService.saveFeedback(runId, request);
    }
}
