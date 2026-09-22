package com.shitan.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 第 17 课单机容量测试：观察名额、有限等待和幂等归还，不启动 Docker 或百炼。
 */
class AdmissionGateTest {

    /**
     * 一个名额被占用时，第二个请求不会无限排队；释放后下一个请求可以继续。
     */
    @Test
    void shouldBoundWaitAndReleasePermit() throws Exception {
        AdmissionGate gate = new AdmissionGate(1, Duration.ofMillis(2));
        AdmissionGate.Lease first = gate.tryAcquire();
        assertNotNull(first);

        assertNull(gate.tryAcquire(), "名额已满时应在短暂等待后返回空，而不是无限阻塞");

        first.close();
        AdmissionGate.Lease second = gate.tryAcquire();
        assertNotNull(second);
        second.close();
    }

    /**
     * 同一租约重复 close 只能释放一次，防止异常路径把并发名额凭空增加。
     */
    @Test
    void shouldReleaseLeaseOnlyOnce() throws Exception {
        AdmissionGate gate = new AdmissionGate(1, Duration.ZERO);
        AdmissionGate.Lease lease = gate.tryAcquire();
        assertNotNull(lease);

        lease.close();
        lease.close();
        AdmissionGate.Lease next = gate.tryAcquire();
        assertNotNull(next);
        next.close();
    }
}
