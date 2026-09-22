package com.shitan.ai;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 为一个资源设置有限名额和有界等待；拿到 Lease 后必须关闭，名额才会归还。
 */
public final class AdmissionGate {

    private final Semaphore permits;
    private final long waitMillis;

    /**
     * 创建一个有界资源闸门；公平参数让等待者按进入顺序获得机会。
     */
    public AdmissionGate(int capacity, Duration waitDuration) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("并发名额必须大于 0");
        }
        this.permits = new Semaphore(capacity, true);
        this.waitMillis = waitDuration.toMillis();
    }

    /**
     * 创建测试和旧构造器使用的无限名额闸门，不改变前面课程的直接调用。
     */
    public static AdmissionGate unbounded() {
        return new AdmissionGate(Integer.MAX_VALUE, Duration.ZERO);
    }

    /**
     * 在限定时间内等待名额；成功返回必须关闭的租约，超时返回空值。
     */
    public Lease tryAcquire() throws InterruptedException {
        if (!permits.tryAcquire(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return null;
        }
        return new Lease(permits);
    }

    /**
     * 一个幂等租约，保证正常返回、异常和取消路径都只释放一次名额。
     */
    public static final class Lease implements AutoCloseable {
        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Semaphore permits) {
            this.permits = permits;
        }

        /**
         * 归还名额；重复 close 不会制造额外许可。
         */
        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
