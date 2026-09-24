package com.agentsflex.graph.importing;

import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.GraphOptions;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证异步导入任务的提交、轮询、失败和取消生命周期。
 */
public class AsyncGraphImportServiceTest {
    private static final GraphImportRequest REQUEST = GraphImportRequest.builder()
        .nodes(Collections.singletonList(GraphNode.builder("n1", "Person").build()))
        .build();

    @Test
    public void shouldRunSuccessfulImportAndExposeFinalReport() throws Exception {
        AsyncGraphImportService service = new AsyncGraphImportService(new StubWriter(GraphWriteResult.success(1, 0)));
        try {
            GraphImportTask submitted = service.submit(REQUEST, GraphOptions.DEFAULT);
            assertNotNull(submitted.getId());
            assertTrue(submitted.getStatus() == GraphImportStatus.QUEUED
                || submitted.getStatus() == GraphImportStatus.RUNNING);

            GraphImportTask completed = awaitTerminal(service, submitted.getId());
            assertEquals(GraphImportStatus.SUCCEEDED, completed.getStatus());
            assertEquals(1, completed.getReport().getNodesImported());
            assertTrue(completed.getCompletedAtMillis() >= completed.getStartedAtMillis());
            assertEquals(1, service.list().size());
            assertTrue(service.remove(completed.getId()));
            assertEquals(0, service.list().size());
        } finally {
            service.close();
        }
    }

    @Test
    public void failedReportShouldProduceFailedTaskWithoutThrowingFromPolling() throws Exception {
        AsyncGraphImportService service = new AsyncGraphImportService(
            new StubWriter(GraphWriteResult.failure("bad row", null)));
        try {
            GraphImportTask task = service.submit(REQUEST, GraphOptions.DEFAULT);
            GraphImportTask completed = awaitTerminal(service, task.getId());
            assertEquals(GraphImportStatus.FAILED, completed.getStatus());
            assertFalse(completed.getReport().isSuccess());
            assertEquals("bad row", completed.getMessage());
        } finally {
            service.close();
        }
    }

    @Test
    public void cancelShouldMoveRunningTaskToCancelled() throws Exception {
        BlockingWriter writer = new BlockingWriter();
        AsyncGraphImportService service = new AsyncGraphImportService(writer);
        try {
            GraphImportTask task = service.submit(REQUEST, GraphOptions.DEFAULT);
            assertTrue(writer.started.await(2, TimeUnit.SECONDS));
            assertTrue(service.cancel(task.getId()));
            GraphImportTask cancelled = service.get(task.getId());
            assertEquals(GraphImportStatus.CANCELLED, cancelled.getStatus());
            assertFalse(service.cancel(task.getId()));
        } finally {
            service.close();
        }
    }

    @Test
    public void unknownTaskAndClosedServiceShouldBeHandledExplicitly() {
        AsyncGraphImportService service = new AsyncGraphImportService(new StubWriter(GraphWriteResult.success(0, 0)));
        assertEquals(null, service.get("missing"));
        service.close();
        try {
            service.submit(REQUEST, GraphOptions.DEFAULT);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("closed import service should reject new tasks");
    }

    /** close 必须中断正在执行的任务并关闭执行器，避免应用热更新或销毁 Bean 时残留线程。 */
    @Test
    public void closeShouldCancelRunningTaskAndTerminateExecutor() throws Exception {
        ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        BlockingWriter writer = new BlockingWriter();
        AsyncGraphImportService service = new AsyncGraphImportService(writer, executor);
        GraphImportTask submitted = service.submit(REQUEST, GraphOptions.DEFAULT);
        assertTrue(writer.started.await(2, TimeUnit.SECONDS));

        service.close();

        assertEquals(GraphImportStatus.CANCELLED, service.get(submitted.getId()).getStatus());
        assertTrue(executor.isShutdown());
        assertTrue("import executor must terminate after close", executor.awaitTermination(2, TimeUnit.SECONDS));
        // 重复关闭应保持幂等。
        service.close();
    }

    @Test
    public void sourceListenerCheckpointAndTaskStoreShouldReceiveLifecycleEvents() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger batches = new AtomicInteger();
        final AtomicInteger checkpoints = new AtomicInteger();
        GraphImportSource source = new GraphImportSource() {
            @Override
            public Iterable<GraphNode> nodes() {
                return Collections.singletonList(GraphNode.builder("source-node", "Person").build());
            }

            @Override
            public Iterable<com.agentsflex.graph.data.GraphEdge> edges() {
                return Collections.emptyList();
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        GraphImportTaskStore store = new InMemoryGraphImportTaskStore();
        GraphImportRequest request = GraphImportRequest.builder()
            .source(source)
            .listener(new GraphImportListener() {
                @Override
                public void onBatchCompleted(GraphImportTask task, GraphWriteResult result) {
                    batches.incrementAndGet();
                }
            })
            .checkpoint((id, nodes, edges) -> checkpoints.incrementAndGet())
            .build();
        AsyncGraphImportService service = new AsyncGraphImportService(
            new StubWriter(GraphWriteResult.success(1, 0)), java.util.concurrent.Executors.newSingleThreadExecutor(), store);
        try {
            GraphImportTask completed = awaitTerminal(service, service.submit(request, GraphOptions.DEFAULT).getId());
            assertEquals(GraphImportStatus.SUCCEEDED, completed.getStatus());
            assertEquals(1, batches.get());
            assertEquals(1, checkpoints.get());
            assertTrue(closed.get());
            assertNotNull(store.get(completed.getId()));
        } finally {
            service.close();
        }
    }

    @Test
    public void resumePointShouldSkipAlreadyCommittedSourceRows() throws Exception {
        final AtomicInteger checkpointNodes = new AtomicInteger();
        GraphImportRequest request = GraphImportRequest.builder()
            .nodes(java.util.Arrays.asList(
                GraphNode.builder("n1", "Person").build(),
                GraphNode.builder("n2", "Person").build(),
                GraphNode.builder("n3", "Person").build()))
            .batchSize(1)
            .resumeFrom(new GraphImportResumePoint(1, 0, 1))
            .checkpoint((taskId, nodes, edges) -> checkpointNodes.set((int) nodes))
            .build();
        AsyncGraphImportService service = new AsyncGraphImportService(new StubWriter(GraphWriteResult.success(1, 0)));
        try {
            GraphImportTask completed = awaitTerminal(service, service.submit(request, GraphOptions.DEFAULT).getId());
            assertEquals(GraphImportStatus.SUCCEEDED, completed.getStatus());
            assertEquals(2, completed.getReport().getNodesImported());
            assertEquals(3, checkpointNodes.get());
            assertEquals(3L, completed.getNodesProcessed());
            assertEquals(3L, completed.getResumePoint().getNodesProcessed());
        } finally {
            service.close();
        }
    }

    @Test
    public void failedBatchShouldNotAdvanceCheckpointWhenContinuing() throws Exception {
        final AtomicReference<String> checkpoint = new AtomicReference<>();
        GraphWriter writer = new GraphWriter() {
            private int calls;

            @Override
            public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
                return calls++ == 0
                    ? GraphWriteResult.failure("bad row", null)
                    : GraphWriteResult.success(1, 0);
            }

            @Override
            public GraphImportReport importData(GraphImportRequest request, GraphOptions options) {
                return new GraphImportReport();
            }
        };
        GraphImportRequest request = GraphImportRequest.builder()
            .nodes(java.util.Arrays.asList(
                GraphNode.builder("n1", "Person").build(),
                GraphNode.builder("n2", "Person").build()))
            .batchSize(1)
            .stopOnError(false)
            .checkpoint((id, nodes, edges) -> checkpoint.set(nodes + ":" + edges))
            .build();
        AsyncGraphImportService service = new AsyncGraphImportService(writer);
        try {
            GraphImportTask completed = awaitTerminal(service, service.submit(request, GraphOptions.DEFAULT).getId());
            assertEquals(GraphImportStatus.FAILED, completed.getStatus());
            assertEquals("1:0", checkpoint.get());
            assertEquals(1, completed.getReport().getNodesImported());
        } finally {
            service.close();
        }
    }

    private static GraphImportTask awaitTerminal(AsyncGraphImportService service, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        GraphImportTask task;
        do {
            task = service.get(id);
            if (task != null && task.isTerminal()) return task;
            Thread.sleep(10L);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("import task did not finish: " + (task == null ? "null" : task.getStatus()));
    }

    private static class StubWriter implements GraphWriter {
        private final GraphWriteResult result;

        protected StubWriter(GraphWriteResult result) {
            this.result = result;
        }

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            return result;
        }

        @Override
        public GraphImportReport importData(GraphImportRequest request, GraphOptions options) {
            GraphImportReport report = new GraphImportReport();
            report.add(result);
            return report;
        }
    }

    private static final class BlockingWriter extends StubWriter {
        private final CountDownLatch started = new CountDownLatch(1);

        private BlockingWriter() {
            super(GraphWriteResult.success(0, 0));
        }

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            started.countDown();
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GraphWriteResult.success(1, 0);
        }
    }
}
