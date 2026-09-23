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
     * 执行只读动作并返回下一轮模型能理解的 Observation。
     */
    String execute(Map<String, String> arguments, AgentScope scope) throws Exception;
}
