package com.agentsflex.graph.importing;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.importing.GraphImportService;
import com.agentsflex.graph.importing.GraphImportStatus;
import com.agentsflex.graph.importing.GraphImportTask;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.GraphException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 基于 {@link GraphWriter} 的异步导入服务默认实现。
 *
 * <p>该类负责任务生命周期、逐批进度快照和取消信号，每批数据通过后端写入器的
 * {@link GraphWriter#mutate(GraphMutation, GraphOptions)} 执行。
 * 默认使用单线程执行器，避免同一个连接上的导入任务互相争抢；产品服务可以通过构造器
 * 注入自己的线程池。</p>
 */
public final class AsyncGraphImportService implements GraphImportService {
    private final GraphWriter writer;
    private final ExecutorService executor;
    private final Map<String, State> tasks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * 使用单线程执行器创建服务。
     */
    public AsyncGraphImportService(GraphWriter writer) {
        this(writer, Executors.newSingleThreadExecutor());
    }

    /**
     * 使用调用方提供的执行器创建服务。
     */
    public AsyncGraphImportService(GraphWriter writer, ExecutorService executor) {
        if (writer == null) throw new IllegalArgumentException("graph writer must not be null");
        if (executor == null) throw new IllegalArgumentException("executor must not be null");
        this.writer = writer;
        this.executor = executor;
    }

    @Override
    public GraphImportTask submit(GraphImportRequest request, GraphOptions options) {
        if (request == null) throw new IllegalArgumentException("import request must not be null");
        if (closed) throw new IllegalStateException("import service is closed");
        State state = new State(UUID.randomUUID().toString(), System.currentTimeMillis());
        tasks.put(state.id, state);
        state.future = executor.submit(() -> run(state, request, options));
        return state.snapshot();
    }

    @Override
    public GraphImportTask get(String id) {
        if (id == null) return null;
        State state = tasks.get(id);
        return state == null ? null : state.snapshot();
    }

    @Override
    public List<GraphImportTask> list() {
        List<GraphImportTask> result = new ArrayList<>();
        for (State state : tasks.values()) result.add(state.snapshot());
        Collections.sort(result, new Comparator<GraphImportTask>() {
            @Override
            public int compare(GraphImportTask left, GraphImportTask right) {
                return Long.compare(right.getSubmittedAtMillis(), left.getSubmittedAtMillis());
            }
        });
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean cancel(String id) {
        State state = id == null ? null : tasks.get(id);
        if (state == null) return false;
        synchronized (state) {
            if (state.status == GraphImportStatus.SUCCEEDED || state.status == GraphImportStatus.FAILED
                || state.status == GraphImportStatus.CANCELLED) return false;
            state.cancelRequested = true;
            state.status = GraphImportStatus.CANCELLED;
            state.message = "Import task cancelled";
            state.completedAtMillis = System.currentTimeMillis();
            if (state.future != null) state.future.cancel(true);
            return true;
        }
    }

    @Override
    public boolean remove(String id) {
        State state = id == null ? null : tasks.get(id);
        if (state == null || !state.snapshot().isTerminal()) return false;
        return tasks.remove(id, state);
    }

    @Override
    public int purgeCompletedBefore(long epochMillis) {
        int removed = 0;
        for (Map.Entry<String, State> entry : tasks.entrySet()) {
            GraphImportTask task = entry.getValue().snapshot();
            if (task.isTerminal() && task.getCompletedAtMillis() >= 0
                && task.getCompletedAtMillis() < epochMillis
                && tasks.remove(entry.getKey(), entry.getValue())) removed++;
        }
        return removed;
    }

    private void run(State state, GraphImportRequest request, GraphOptions options) {
        synchronized (state) {
            if (state.cancelRequested) return;
            state.status = GraphImportStatus.RUNNING;
            state.startedAtMillis = System.currentTimeMillis();
        }
        try {
            importNodes(state, request, options);
            importEdges(state, request, options);
            synchronized (state) {
                if (state.cancelRequested) return;
                state.status = state.report.isSuccess() ? GraphImportStatus.SUCCEEDED : GraphImportStatus.FAILED;
                state.message = state.report.isSuccess() ? "" : "Import completed with failed batches";
                state.completedAtMillis = System.currentTimeMillis();
            }
        } catch (Throwable error) {
            synchronized (state) {
                if (state.cancelRequested) return;
                state.status = GraphImportStatus.FAILED;
                state.message = error.getMessage() == null ? error.getClass().getName() : error.getMessage();
                state.completedAtMillis = System.currentTimeMillis();
            }
        }
    }

    private void importNodes(State state, GraphImportRequest request, GraphOptions options) {
        List<GraphNode> batch = new ArrayList<>();
        for (GraphNode node : request.getNodes()) {
            checkCancelled(state);
            batch.add(node);
            if (batch.size() >= request.getBatchSize()) {
                writeBatch(state, GraphMutation.builder().upsertNodes(batch).build(), options,
                    request.isStopOnError());
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            writeBatch(state, GraphMutation.builder().upsertNodes(batch).build(), options,
                request.isStopOnError());
        }
    }

    private void importEdges(State state, GraphImportRequest request, GraphOptions options) {
        List<GraphEdge> batch = new ArrayList<>();
        for (GraphEdge edge : request.getEdges()) {
            checkCancelled(state);
            batch.add(edge);
            if (batch.size() >= request.getBatchSize()) {
                writeBatch(state, GraphMutation.builder().upsertEdges(batch).build(), options,
                    request.isStopOnError());
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            writeBatch(state, GraphMutation.builder().upsertEdges(batch).build(), options,
                request.isStopOnError());
        }
    }

    private void writeBatch(State state, GraphMutation mutation, GraphOptions options, boolean stopOnError) {
        checkCancelled(state);
        GraphWriteResult result = writer.mutate(mutation, options);
        state.report.add(result);
        if (stopOnError && !result.isSuccess()) {
            throw new GraphException(result.getMessage(), result.getError());
        }
    }

    private void checkCancelled(State state) {
        if (state.cancelRequested || Thread.currentThread().isInterrupted()) {
            throw new ImportCancelledException();
        }
    }

    @Override
    public void close() {
        closed = true;
        for (State state : tasks.values()) {
            synchronized (state) {
                if (!state.status.equals(GraphImportStatus.SUCCEEDED)
                    && !state.status.equals(GraphImportStatus.FAILED)
                    && !state.status.equals(GraphImportStatus.CANCELLED)) {
                    state.cancelRequested = true;
                    state.status = GraphImportStatus.CANCELLED;
                    state.message = "Import service closed";
                    state.completedAtMillis = System.currentTimeMillis();
                }
            }
        }
        executor.shutdownNow();
    }

    private static final class State {
        private final String id;
        private final long submittedAtMillis;
        private volatile GraphImportStatus status = GraphImportStatus.QUEUED;
        private volatile GraphImportReport report = new GraphImportReport();
        private volatile String message = "";
        private volatile long startedAtMillis = -1L;
        private volatile long completedAtMillis = -1L;
        private volatile boolean cancelRequested;
        private volatile Future<?> future;

        private State(String id, long submittedAtMillis) {
            this.id = id;
            this.submittedAtMillis = submittedAtMillis;
        }

        private GraphImportTask snapshot() {
            synchronized (this) {
                return new GraphImportTask(id, status, report, message, submittedAtMillis,
                    startedAtMillis, completedAtMillis);
            }
        }
    }

    /**
     * 仅用于快速退出工作线程，取消状态已经由 cancel/close 写入任务快照。
     */
    private static final class ImportCancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
