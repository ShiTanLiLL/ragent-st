package com.shitan.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 16 课小测试：用一个可控的调用函数观察首选模型失败后的切换和熔断状态。
 * 真实百炼测试仍由后续 IT 用例负责；这里不伪造 HTTP，只验证路由器自身的状态变化。
 */
class ModelRoutingTest {

    /**
     * 首选 standard 模型失败后只尝试一次，随后切换备用模型；第二次请求跳过仍处于 OPEN 的首选模型。
     */
    @Test
    void shouldFallbackAndOpenFailedModel() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();
        ChatModelInvoker invoker = (model, question, evidences) -> {
            if (model.equals("primary")) {
                primaryCalls.incrementAndGet();
                throw new IOException("模拟首包失败");
            }
            backupCalls.incrementAndGet();
            return "备用模型回答";
        };
        ModelRoutingService routing = new ModelRoutingService(invoker, List.of(
                new ManagedChatModel("primary", ModelTier.STANDARD, 0),
                new ManagedChatModel("backup", ModelTier.STANDARD, 1)
        ));

        String first = routing.generate(ModelTier.STANDARD, "问题", List.of());
        String second = routing.generate(ModelTier.STANDARD, "问题", List.of());

        assertEquals("备用模型回答", first);
        assertEquals("备用模型回答", second);
        assertEquals(1, primaryCalls.get(), "首选模型第一次失败后应被熔断，不能每次请求重复撞它");
        assertEquals(2, backupCalls.get());
        assertEquals(ModelHealthState.OPEN, routing.healthStates().get("primary"));
        assertEquals(ModelHealthState.CLOSED, routing.healthStates().get("backup"));
    }

    /**
     * fast 请求优先选择 fast 档位；若 fast 不可用，再按优先级尝试其他档位。
     */
    @Test
    void shouldPreferRequestedTier() throws Exception {
        AtomicInteger fastCalls = new AtomicInteger();
        ChatModelInvoker invoker = (model, question, evidences) -> {
            if (model.equals("fast")) {
                fastCalls.incrementAndGet();
            }
            return model;
        };
        ModelRoutingService routing = new ModelRoutingService(invoker, List.of(
                new ManagedChatModel("standard", ModelTier.STANDARD, 0),
                new ManagedChatModel("fast", ModelTier.FAST, 1)
        ));

        assertEquals("fast", routing.generate(ModelTier.FAST, "问题", List.of()));
        assertTrue(fastCalls.get() == 1);
    }

    /**
     * 冷却结束后只放行一次 HALF_OPEN 探测；探测成功后模型回到 CLOSED 并重新成为首选。
     */
    @Test
    void shouldProbeAndRecoverAfterCooldown() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        ChatModelInvoker invoker = (model, question, evidences) -> {
            if (primaryCalls.incrementAndGet() == 1) {
                throw new IOException("第一次调用失败");
            }
            return "恢复后的首选模型回答";
        };
        ManagedChatModel primary = new ManagedChatModel("primary", ModelTier.STANDARD, 0, 1L);
        ModelRoutingService routing = new ModelRoutingService(invoker, List.of(primary));

        try {
            routing.generate(ModelTier.STANDARD, "问题", List.of());
        } catch (IOException expected) {
            // 只有一个候选，第一次失败后没有备用模型是本测试的前置状态。
        }
        Thread.sleep(5L);

        assertEquals("恢复后的首选模型回答", routing.generate(
                ModelTier.STANDARD, "问题", List.of()));
        assertEquals(ModelHealthState.CLOSED, primary.state());
    }
}
