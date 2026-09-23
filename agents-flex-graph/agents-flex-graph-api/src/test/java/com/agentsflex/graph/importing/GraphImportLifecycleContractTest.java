package com.agentsflex.graph.importing;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖异步导入服务容易被忽略的生命周期契约：顺序、批次、监听器隔离和排队取消。
 */
public class GraphImportLifecycleContractTest {
    @Test
    public void nodesMustBeWrittenBeforeEdgesAndBatchSizeMustBeRespected() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        AsyncGraphImportService service = new AsyncGraphImportService(writer);
        try {
            GraphImportRequest request = GraphImportRequest.builder()
                .nodes(Arrays.asList(node("n1"), node("n2"), node("n3")))
                .edges(Arrays.asList(edge("n1", "n2"), edge("n2", "n3")))
                .batchSize(2)
                .build();
            GraphImportTask task = awaitTerminal(service, service.submit(request, GraphOptions.DEFAULT).getId());

            assertEquals(GraphImportStatus.SUCCEEDED, task.getStatus());
            assertEquals(Arrays.asList("N:2", "N:1", "E:2"), writer.calls);
            assertEquals(3L, task.getNodesProcessed());
            assertEquals(2L, task.getEdgesProcessed());
            assertEquals(3, task.getBatchesProcessed());
        } finally {
            service.close();
        }
    }

    @Test
    public void listenerExceptionsMustNotChangeSuccessfulTask() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        AtomicInteger progress = new AtomicInteger();
        GraphImportRequest request = GraphImportRequest.builder()
            .nodes(Collections.singletonList(node("n1")))
            .listener(new GraphImportListener() {
                @Override
                public void onProgress(GraphImportTask task) {
                    progress.incrementAndGet();
                    throw new RuntimeException("listener progress failure");
                }

                @Override
                public void onBatchCompleted(GraphImportTask task, GraphWriteResult result) {
                    throw new RuntimeException("listener batch failure");
                }
            })
            .build();
        AsyncGraphImportService service = new AsyncGraphImportService(writer);
        try {
            GraphImportTask task = awaitTerminal(service, service.submit(request, GraphOptions.DEFAULT).getId());
            assertEquals(GraphImportStatus.SUCCEEDED, task.getStatus());
            assertTrue(progress.get() > 0);
        } finally {
            service.close();
        }
    }

    @Test
    public void queuedCancellationMustCloseSourceOnceAndNeverExecuteItsBatch() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BlockingWriter writer = new BlockingWriter();
        AtomicInteger closed = new AtomicInteger();
        AsyncGraphImportService service = new AsyncGraphImportService(writer, executor);
        try {
            GraphImportRequest first = GraphImportRequest.builder()
                .nodes(Collections.singletonList(node("first"))).build();
            GraphImportRequest queued = GraphImportRequest.builder()
                .source(new GraphImportSource() {
                    @Override
                    public Iterable<GraphNode> nodes() {
                        return Collections.singletonList(node("queued"));
                    }

                    @Override
                    public Iterable<GraphEdge> edges() {
                        return Collections.emptyList();
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                }).build();
            GraphImportTask running = service.submit(first, GraphOptions.DEFAULT);
            assertTrue(writer.started.await(2, TimeUnit.SECONDS));
            GraphImportTask waiting = service.submit(queued, GraphOptions.DEFAULT);
            assertTrue(service.cancel(waiting.getId()));
            assertEquals(GraphImportStatus.CANCELLED, service.get(waiting.getId()).getStatus());
            assertEquals(1, closed.get());
            writer.release.countDown();
            assertEquals(GraphImportStatus.SUCCEEDED, awaitTerminal(service, running.getId()).getStatus());
            assertEquals(Collections.singletonList("N:1"), writer.calls);
        } finally {
            writer.release.countDown();
            service.close();
        }
    }

    @Test
    public void taskStoreShouldTrackRemoveAndPurgeCompletedTasks() throws Exception {
        InMemoryGraphImportTaskStore store = new InMemoryGraphImportTaskStore();
        AsyncGraphImportService service = new AsyncGraphImportService(
            new RecordingWriter(), Executors.newSingleThreadExecutor(), store);
        try {
            GraphImportTask task = awaitTerminal(service, service.submit(
                GraphImportRequest.builder().nodes(Collections.singletonList(node("n1"))).build(),
                GraphOptions.DEFAULT).getId());
            assertTrue(store.get(task.getId()) != null);
            assertFalse(service.remove("missing"));
            assertTrue(service.remove(task.getId()));
            assertEquals(null, service.get(task.getId()));

            GraphImportTask purged = awaitTerminal(service, service.submit(
                GraphImportRequest.builder().nodes(Collections.singletonList(node("n2"))).build(),
                GraphOptions.DEFAULT).getId());
            assertEquals(1, service.purgeCompletedBefore(purged.getCompletedAtMillis() + 1));
            assertEquals(null, service.get(purged.getId()));
        } finally {
            service.close();
        }
    }

    @Test
    public void cancellationAfterCompletionMustNotRewriteSuccessfulTask() throws Exception {
        AsyncGraphImportService service = new AsyncGraphImportService(new RecordingWriter());
        try {
            GraphImportTask completed = awaitTerminal(service, service.submit(
                GraphImportRequest.builder().nodes(Collections.singletonList(node("done"))).build(),
                GraphOptions.DEFAULT).getId());
            assertEquals(GraphImportStatus.SUCCEEDED, completed.getStatus());
            assertFalse(service.cancel(completed.getId()));
            assertEquals(GraphImportStatus.SUCCEEDED, service.get(completed.getId()).getStatus());
        } finally {
            service.close();
        }
    }

    @Test
    public void customExecutorShouldAllowMultipleImportsToRunConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        RecordingWriter writer = new RecordingWriter();
        AsyncGraphImportService service = new AsyncGraphImportService(writer, executor);
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                ids.add(service.submit(GraphImportRequest.builder()
                    .nodes(Collections.singletonList(node("n" + i))).build(), GraphOptions.DEFAULT).getId());
            }
            for (String id : ids) {
                assertEquals(GraphImportStatus.SUCCEEDED, awaitTerminal(service, id).getStatus());
            }
            assertEquals(8, writer.calls.size());
        } finally {
            service.close();
        }
    }

    private static GraphNode node(String id) {
        return GraphNode.builder(id, "Person").build();
    }

    private static GraphEdge edge(String from, String to) {
        return GraphEdge.builder(from, "KNOWS", to).build();
    }

    private static GraphImportTask awaitTerminal(AsyncGraphImportService service, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        GraphImportTask task;
        do {
            task = service.get(id);
            if (task != null && task.isTerminal()) return task;
            Thread.sleep(5L);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("import task did not finish: " + (task == null ? "null" : task.getStatus()));
    }

    private static class RecordingWriter implements GraphWriter {
        protected final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            String prefix = mutation.getNodes().isEmpty() ? "E:" : "N:";
            calls.add(prefix + (mutation.getNodes().isEmpty() ? mutation.getEdges().size() : mutation.getNodes().size()));
            return GraphWriteResult.success(mutation.getNodes().size(), mutation.getEdges().size());
        }

        @Override
        public GraphImportReport importData(GraphImportRequest request, GraphOptions options) {
            return new GraphImportReport();
        }
    }

    private static final class BlockingWriter extends RecordingWriter {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return super.mutate(mutation, options);
        }
    }
}
