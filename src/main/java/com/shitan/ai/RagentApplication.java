package com.shitan.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
     * @param bailianClient       Spring 容器中已经创建的百炼客户端
     * @param knowledgeRepository 保存知识并执行 pgvector 检索的 PostgreSQL 仓库
     * @return 能执行“向量化 → 检索 → 生成”的问答助手
     */
    @Bean
    BailianRagAssistant bailianRagAssistant(
            BailianClient bailianClient,
            KnowledgeRepository knowledgeRepository,
            HybridRetrievalService hybridRetrievalService,
            ModelRoutingService modelRoutingService
    ) {
        return new BailianRagAssistant(
                bailianClient,
                knowledgeRepository,
                hybridRetrievalService,
                modelRoutingService
        );
    }

    /**
     * 创建流式问答使用的后台线程池，使 HTTP 请求线程可以先把 SSE 连接交还给 Spring。
     *
     * @return 最多同时执行四个模型流式任务的线程池；应用关闭时由 Spring 调用 shutdown
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService streamExecutor() {
        return Executors.newFixedThreadPool(4);
    }

    /**
     * 创建文档摄取专用线程池，使上传请求登记任务后可以立即返回 HTTP 202。
     *
     * @return 最多同时处理两个文档的后台线程池；应用关闭时由 Spring 调用 shutdown
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService ingestionExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    /**
     * 创建检索通道专用线程池，让向量 Embedding 和关键词数据库查询能够并行开始。
     *
     * @return 最多同时执行两个召回通道的线程池
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService retrievalExecutor() {
        return Executors.newFixedThreadPool(2);
    }
}
