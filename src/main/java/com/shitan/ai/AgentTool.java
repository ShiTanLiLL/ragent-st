package com.shitan.ai;

import java.util.Map;

/**
 * Agent 可以调用的一项受控本地能力；工具只接收经过 Java 校验的参数和服务端 Scope。
 */
public interface AgentTool {

    /**
     * 返回模型决策时使用的稳定工具名。
     */
    String name();

    /**
     * 返回工具用途和参数说明，不暴露 Java 实现细节。
     */
    AgentToolDefinition definition();

    /**
     * 标明工具是否只读；写工具必须先进入用户确认流程，不能由模型直接执行。
     */
    default boolean readOnly() {
        return true;
    }

    /**
     * 返回执行前必须加载的 Skill 编号；null 表示没有手册前置要求。
     */
    default String requiredSkillCode() {
        return null;
    }

    /**
     * 执行动作并返回下一轮模型能理解的 Observation；写动作只能在确认服务放行后进入这里。
     */
    String execute(Map<String, String> arguments, AgentScope scope) throws Exception;
}
