package com.agentsflex.graph.extractor.incremental;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 本地文档锁的作用域、互斥和租约释放测试。
 */
public class LocalGraphIngestionLockProviderTest {
    /**
     * 同一 Space 和文档必须互斥，不同文档不能被服务级全局锁阻塞。
     */
    @Test
    public void shouldSerializeSameDocumentWithoutBlockingDifferentDocument() throws Exception {
        LocalGraphIngestionLockProvider provider = new LocalGraphIngestionLockProvider();
        GraphIngestionLockProvider.Lease first = provider.acquire("knowledge", "doc-1");
        CountDownLatch sameDocumentAcquired = new CountDownLatch(1);
        CountDownLatch otherDocumentAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sameDocument = executor.submit(() -> {
                try (GraphIngestionLockProvider.Lease ignored = provider.acquire("knowledge", "doc-1")) {
                    sameDocumentAcquired.countDown();
                }
            });
            Future<?> otherDocument = executor.submit(() -> {
                try (GraphIngestionLockProvider.Lease ignored = provider.acquire("knowledge", "doc-2")) {
                    otherDocumentAcquired.countDown();
                }
            });

            assertTrue("another document should not wait for doc-1", otherDocumentAcquired.await(1,
                TimeUnit.SECONDS));
            assertFalse("the same document must remain blocked", sameDocumentAcquired.await(150,
                TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(sameDocumentAcquired.await(1, TimeUnit.SECONDS));
            sameDocument.get(1, TimeUnit.SECONDS);
            otherDocument.get(1, TimeUnit.SECONDS);
            // Lease 必须允许幂等 close，避免 finally 与显式关闭叠加时重复 unlock。
            first.close();
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    /**
     * 相同文档 ID 位于不同 Space 时必须拥有独立锁域。
     */
    @Test
    public void shouldIsolateSameDocumentIdAcrossSpaces() throws Exception {
        LocalGraphIngestionLockProvider provider = new LocalGraphIngestionLockProvider();
        GraphIngestionLockProvider.Lease first = provider.acquire("knowledge", "doc-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> otherSpace = executor.submit(() -> {
                try (GraphIngestionLockProvider.Lease ignored = provider.acquire("archive", "doc-1")) {
                    return true;
                }
            });
            assertTrue(otherSpace.get(1, TimeUnit.SECONDS));
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    /**
     * 同线程重入必须按租约层级释放，内层关闭不能提前释放外层锁。
     */
    @Test
    public void reentrantLeaseShouldReleaseOnlyItsOwnHold() throws Exception {
        LocalGraphIngestionLockProvider provider = new LocalGraphIngestionLockProvider();
        GraphIngestionLockProvider.Lease outer = provider.acquire("knowledge", "doc-1");
        GraphIngestionLockProvider.Lease inner = provider.acquire("knowledge", "doc-1");
        inner.close();
        CountDownLatch acquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> {
                try (GraphIngestionLockProvider.Lease ignored = provider.acquire("knowledge", "doc-1")) {
                    acquired.countDown();
                }
            });
            assertFalse(acquired.await(150, TimeUnit.MILLISECONDS));
            outer.close();
            assertTrue(acquired.await(1, TimeUnit.SECONDS));
            waiter.get(1, TimeUnit.SECONDS);
        } finally {
            outer.close();
            executor.shutdownNow();
        }
    }

    /**
     * try-with-resources 内的业务异常不能导致文档锁永久泄漏。
     */
    @Test
    public void exceptionalScopeShouldStillReleaseLease() throws Exception {
        LocalGraphIngestionLockProvider provider = new LocalGraphIngestionLockProvider();
        try {
            try (GraphIngestionLockProvider.Lease ignored = provider.acquire("knowledge", "doc-1")) {
                throw new IllegalStateException("synthetic failure");
            }
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("synthetic"));
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> next = executor.submit(() -> {
                try (GraphIngestionLockProvider.Lease ignored = provider.acquire("knowledge", "doc-1")) {
                    return true;
                }
            });
            assertTrue(next.get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 空白作用域标识应在进入锁表前被拒绝。
     */
    @Test
    public void shouldRejectBlankLockIdentity() {
        LocalGraphIngestionLockProvider provider = new LocalGraphIngestionLockProvider();
        try {
            provider.acquire(" ", "doc-1");
            fail("blank space must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("space"));
        }
        try {
            provider.acquire("knowledge", null);
            fail("null document id must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("documentId"));
        }
    }
}
