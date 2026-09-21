package com.shitan.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供本课观察会话消息和记忆窗口的只读 HTTP 入口；所有查询都带 userId 做归属校验。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationQuestionService conversationQuestionService;

    /**
     * 保存会话问答服务，Controller 只负责路径和查询参数转换。
     *
     * @param conversationQuestionService 会话问答和记忆读取服务
     */
    public ConversationController(ConversationQuestionService conversationQuestionService) {
        this.conversationQuestionService = conversationQuestionService;
    }

    /**
     * 返回当前用户在该会话中的全部消息，按数据库生成编号升序排列。
     *
     * @param conversationId URL 中的会话编号
     * @param userId         查询者用户标识
     * @return user/assistant 消息列表
     */
    @GetMapping("/{conversationId}/messages")
    public List<ConversationMessage> messages(
            @PathVariable("conversationId") String conversationId,
            @RequestParam("userId") String userId
    ) {
        return conversationQuestionService.messages(conversationId, userId);
    }

    /**
     * 返回模型本轮可见的摘要和最近原文窗口，帮助理解摘要水位如何变化。
     *
     * @param conversationId URL 中的会话编号
     * @param userId         查询者用户标识
     * @return 有界会话记忆快照
     */
    @GetMapping("/{conversationId}/memory")
    public ConversationMemory memory(
            @PathVariable("conversationId") String conversationId,
            @RequestParam("userId") String userId
    ) {
        return conversationQuestionService.memory(conversationId, userId);
    }
}
