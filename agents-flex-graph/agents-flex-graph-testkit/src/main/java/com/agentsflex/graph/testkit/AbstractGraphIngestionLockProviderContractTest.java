package com.agentsflex.graph.testkit;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionLockProvider;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link GraphIngestionLockProvider} 实现可复用的互斥和作用域契约测试。
 */
public abstract class AbstractGraphIngestionLockProviderContractTest {
    /**
     * 当前测试使用的全新锁实现。
     */
    protected GraphIngestionLockProvider provider;

    /**
     * 创建不持有任何租约的锁实现。
     */
    protected abstract GraphIngestionLockProvider createProvider();

    /**
     * 每个测试重新创建锁实现。
     */
    @Before
    public void setUpGraphIngestionLockProviderContract() {
        provider = createProvider();
        if (provider == null) throw new IllegalStateException("createProvider must not return null");
    }

    /**
     * 同一 Space 和 documentId 必须互斥，租约释放后等待者才能进入。
     */
    @Test
    public void sameDocumentMustBeMutuallyExclusive() throws Exception {
        GraphIngestionLockProvider.Lease first = provider.acquire("space_a", "doc-1");
        CountDownLatch acquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> {
                try (GraphIngestionLockProvider.Lease ignored = provider.acquire("space_a", "doc-1")) {
                    acquired.countDown();
                }
            });
            assertFalse(acquired.await(150, TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            waiter.get(2, TimeUnit.SECONDS);
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    /**
     * 不同文档和不同 Space 必须拥有独立锁域。
     */
    @Test
    public void differentScopesMustNotBlockEachOther() throws Exception {
        GraphIngestionLockProvider.Lease first = provider.acquire("space_a", "doc-1");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> otherDocument = executor.submit(() -> acquireAndRelease("space_a", "doc-2"));
            Future<Boolean> otherSpace = executor.submit(() -> acquireAndRelease("space_b", "doc-1"));
            assertTrue(otherDocument.get(2, TimeUnit.SECONDS));
            assertTrue(otherSpace.get(2, TimeUnit.SECONDS));
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    /**
     * 获取并立即释放指定作用域，用于验证无关锁域不会阻塞。
     */
    private boolean acquireAndRelease(String space, String documentId) {
        try (GraphIngestionLockProvider.Lease ignored = provider.acquire(space, documentId)) {
            return true;
        }
    }
}
