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
 * 开放任务 HTTP 入口；固定 RAG `/api/questions` 保持不变。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    /**
     * 保存 Agent 循环服务。
     */
    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 创建或恢复 Agent 会话并运行当前一轮，直到回答、失败或达到迭代上限。
     */
    @PostMapping("/tasks")
    public AgentResponse run(@Valid @RequestBody AgentRequest request) throws Exception {
        return agentService.run(request);
    }

    /**
     * 查询当前用户的一次 Agent 会话和持久化步骤。
     */
    @GetMapping("/sessions/{sessionId}")
    public AgentResponse session(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("userId") String userId
    ) {
        return agentService.get(sessionId, userId);
    }

    /**
     * 批准或拒绝一张写工具确认卡；请求体不能重传工具名和参数。
     */
    @PostMapping("/confirmations/{confirmationId}")
    public AgentResponse confirm(
            @PathVariable("confirmationId") String confirmationId,
            @Valid @RequestBody AgentConfirmationRequest request
    ) throws Exception {
        return agentService.confirm(confirmationId, request);
    }
}
