package com.shitan.ai;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 按需加载业务 Skill 手册；模型只先看到清单，真正办理时才把完整步骤放进历史。
 */
@Component
public class SkillManualAgentTool implements AgentTool {

    private static final String RETURN_REQUEST = "return_request";
    private final String returnRequestManual;

    /**
     * 从 classpath 读取版本化手册，缺少资源时让应用启动立即失败。
     */
    public SkillManualAgentTool() throws IOException {
        ClassPathResource resource = new ClassPathResource("skills/return-request.md");
        try (var input = resource.getInputStream()) {
            this.returnRequestManual = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 返回给决策模型使用的稳定工具名。
     */
    @Override
    public String name() {
        return "load_skill";
    }

    /**
     * 告诉模型当前可加载的手册编号以及调用所需参数。
     */
    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                name(),
                "加载复杂业务的操作手册。可用 skillCode：return_request（创建退货申请）",
                List.of("skillCode")
        );
    }

    /**
     * 返回指定 Skill 正文；未知编号明确失败，避免模型把不存在的手册当作已加载。
     */
    @Override
    public String execute(Map<String, String> arguments, AgentScope scope) {
        String code = arguments.get("skillCode");
        if (!RETURN_REQUEST.equals(code)) {
            throw new IllegalArgumentException("Skill 不存在或未启用：" + code);
        }
        return "已加载 Skill " + RETURN_REQUEST + "：\n" + returnRequestManual;
    }
}
