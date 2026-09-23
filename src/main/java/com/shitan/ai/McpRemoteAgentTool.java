package com.shitan.ai;

import java.util.Map;

/**
 * 把 tools/list 发现的远端工具适配成 AgentTool，使 AgentService 不需要理解 MCP HTTP。
 */
public final class McpRemoteAgentTool implements AgentTool {

    private final McpToolClient client;
    private final McpDiscoveredTool remote;

    /**
     * 保存共享 MCP 客户端和一份启动期工具定义快照。
     */
    public McpRemoteAgentTool(McpToolClient client, McpDiscoveredTool remote) {
        this.client = client;
        this.remote = remote;
    }

    /**
     * 沿用远端公布的稳定工具名，供 Agent 白名单和 tools/call 使用同一标识。
     */
    @Override
    public String name() {
        return remote.name();
    }

    /**
     * 把远端说明、必填参数、只读属性和本地 Skill 策略翻译成 Agent 可见定义。
     */
    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                remote.name(),
                remote.description(),
                remote.argumentNames(),
                remote.readOnly(),
                remote.requiredSkillCode()
        );
    }

    /**
     * 返回发现阶段保存的只读提示，AgentService 据此决定是否进入确认流程。
     */
    @Override
    public boolean readOnly() {
        return remote.readOnly();
    }

    /**
     * 返回主应用绑定的前置 Skill；没有绑定时返回 null。
     */
    @Override
    public String requiredSkillCode() {
        return remote.requiredSkillCode();
    }

    /**
     * 把模型参数和 Scope 中可信 userId 发给远端；写工具只能由确认恢复路径调用本方法。
     */
    @Override
    public String execute(Map<String, String> arguments, AgentScope scope) throws Exception {
        return client.callTool(remote.name(), arguments, scope.userId());
    }
}
