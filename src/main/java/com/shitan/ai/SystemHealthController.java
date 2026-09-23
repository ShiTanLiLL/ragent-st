package com.shitan.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露教学系统端到端健康状态，方便本机和容器启动后快速检查依赖。
 */
@RestController
@RequestMapping("/api/system")
public class SystemHealthController {

    private final SystemHealthService healthService;

    /**
     * 保存健康检查服务。
     */
    public SystemHealthController(SystemHealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * 返回数据库和 MCP 连通性；详细业务正确性仍由课程集成测试负责。
     */
    @GetMapping("/health")
    public SystemHealthResponse health() {
        return healthService.health();
    }
}
