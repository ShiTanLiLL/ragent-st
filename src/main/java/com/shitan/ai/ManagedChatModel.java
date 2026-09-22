package com.shitan.ai;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 保存一个模型候选的静态档位和动态健康状态；每个候选拥有自己的熔断状态。
 */
public final class ManagedChatModel {

    private final String model;
    private final ModelTier tier;
    private final int priority;
    private final long cooldownNanos;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile ModelHealthState state = ModelHealthState.CLOSED;
    private volatile long openUntilNanos;
    private boolean halfOpenProbeInFlight;

    /**
     * 创建一个候选模型；priority 越小越优先尝试。
     */
    public ManagedChatModel(String model, ModelTier tier, int priority) {
        this(model, tier, priority, 2_000L);
    }

    /**
     * 创建可指定冷却时间的候选；测试使用很短冷却观察 HALF_OPEN，生产默认两秒。
     */
    public ManagedChatModel(String model, ModelTier tier, int priority, long cooldownMillis) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("冷却时间不能为负数");
        }
        this.model = model;
        this.tier = tier;
        this.priority = priority;
        this.cooldownNanos = cooldownMillis * 1_000_000L;
    }

    /**
     * 返回供应商真正需要的模型名。
     */
    public String model() {
        return model;
    }

    /**
     * 返回面向业务请求的速度/能力档位。
     */
    public ModelTier tier() {
        return tier;
    }

    /**
     * 返回配置优先级。
     */
    public int priority() {
        return priority;
    }

    /**
     * 在没有并发探测时允许调用；OPEN 冷却结束后转换为 HALF_OPEN，只放行一次探测。
     */
    public synchronized boolean tryAcquire() {
        if (state == ModelHealthState.CLOSED) {
            return true;
        }
        if (state == ModelHealthState.OPEN && System.nanoTime() >= openUntilNanos) {
            state = ModelHealthState.HALF_OPEN;
            halfOpenProbeInFlight = true;
            return true;
        }
        if (state == ModelHealthState.HALF_OPEN && !halfOpenProbeInFlight) {
            halfOpenProbeInFlight = true;
            return true;
        }
        return false;
    }

    /**
     * 成功响应说明候选恢复，清空失败次数并关闭熔断。
     */
    public synchronized void markSuccess() {
        consecutiveFailures.set(0);
        state = ModelHealthState.CLOSED;
        halfOpenProbeInFlight = false;
    }

    /**
     * 一次调用失败就暂时隔离该候选；冷却两秒后允许 HALF_OPEN 探测。
     */
    public synchronized void markFailure() {
        consecutiveFailures.incrementAndGet();
        state = ModelHealthState.OPEN;
        openUntilNanos = System.nanoTime() + cooldownNanos;
        halfOpenProbeInFlight = false;
    }

    /**
     * 外部容量闸门没有放行时归还 HALF_OPEN 探测资格；这不是模型失败，不能把候选熔断。
     */
    public synchronized void releaseProbeWithoutCall() {
        if (state == ModelHealthState.HALF_OPEN) {
            halfOpenProbeInFlight = false;
        }
    }

    /**
     * 返回测试和观测使用的当前健康状态。
     */
    public ModelHealthState state() {
        return state;
    }
}
