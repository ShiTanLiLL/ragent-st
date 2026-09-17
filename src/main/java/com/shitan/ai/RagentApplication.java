package com.shitan.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 教学项目的启动入口，负责启动 Spring 容器和内置 Web 服务器。
 */
@SpringBootApplication
public class RagentApplication {

    /**
     * 启动整个应用；Spring 会扫描当前包中的 Controller 和异常处理器，随后监听 HTTP 端口。
     *
     * @param args 命令行启动参数，当前课程不需要额外参数
     */
    public static void main(String[] args) {
        SpringApplication.run(RagentApplication.class, args);
    }

    /**
     * 创建唯一的百炼客户端，从已经配置好的永久环境变量读取 API Key。
     *
     * @return 可以调用百炼 Embedding 与 Chat 接口的客户端
     */
    @Bean
    BailianClient bailianClient() {
        return BailianClient.fromEnvironment();
    }

    /**
     * 创建 Web 层实际使用的 RAG 助手，并把同一个百炼客户端交给它。
     *
     * @param bailianClient Spring 容器中已经创建的百炼客户端
     * @return 能执行“向量化 → 检索 → 生成”的问答助手
     */
    @Bean
    BailianRagAssistant bailianRagAssistant(BailianClient bailianClient) {
        return new BailianRagAssistant(bailianClient);
    }
}
