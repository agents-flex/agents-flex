package com.agentsflex.graph.extractor.incremental;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按文档键隔离、线程安全的进程内文档锁。
 *
 * <p>等待者在获取底层锁前就会增加引用计数，最后一个持有者释放后才移除锁对象，因此不会出现同一
 * 文档同时使用两把锁的竞态；已经空闲的文档键也不会永久占用内存。该实现不能协调多个 JVM，因此
 * 不应冒充生产分布式锁。</p>
 */
public final class LocalGraphIngestionLockProvider implements GraphIngestionLockProvider {
    /**
     * 文档作用域键到当前锁及其持有者、等待者总引用数。
     */
    private final ConcurrentMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    /**
     * 获取由 Space 和文档 ID 共同定位的本地锁。
     */
    @Override
    public Lease acquire(String space, String documentId) {
        final String key = text(space, "space") + "\u0000" + text(documentId, "documentId");
        final LockEntry entry = locks.compute(key, (ignored, current) -> {
            LockEntry value = current == null ? new LockEntry() : current;
            value.references++;
            return value;
        });
        entry.lock.lock();
        return new Lease() {
            /** 防止调用方重复 close 导致错误释放其他重入层级。 */
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) return;
                entry.lock.unlock();
                locks.computeIfPresent(key, (ignored, current) -> {
                    if (current != entry) return current;
                    current.references--;
                    return current.references == 0 ? null : current;
                });
            }
        };
    }

    /**
     * 同一个文档键对应的锁及受 ConcurrentMap.compute 保护的引用数。
     */
    private static final class LockEntry {
        /**
         * 非公平可重入锁，允许 ingest 在内部复用同线程调用路径。
         */
        private final ReentrantLock lock = new ReentrantLock();
        /**
         * 当前持有者和等待者总数。
         */
        private int references;
    }

    /**
     * 校验并裁剪锁作用域标识。
     */
    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
