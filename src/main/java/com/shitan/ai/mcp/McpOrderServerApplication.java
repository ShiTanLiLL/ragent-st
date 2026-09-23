package com.shitan.ai.mcp;

import java.util.concurrent.CountDownLatch;

/**
 * 独立 MCP 工具进程入口；它与 Ragent Spring 应用通过 HTTP 通信，不共享 Java 对象。
 */
public final class McpOrderServerApplication {

    /**
     * 纯启动入口不需要实例对象，因此禁止外部构造。
     */
    private McpOrderServerApplication() {
    }

    /**
     * 在8091端口启动工具服务并保持进程运行；Ctrl+C 时关闭监听和线程池。
     */
    public static void main(String[] args) throws Exception {
        McpOrderServer server = McpOrderServer.start(8091);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("MCP 订单工具服务已启动：" + server.endpoint());
        new CountDownLatch(1).await();
    }
}
