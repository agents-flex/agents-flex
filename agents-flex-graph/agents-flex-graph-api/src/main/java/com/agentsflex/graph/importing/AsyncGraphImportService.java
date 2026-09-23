package com.agentsflex.graph.importing;

import com.agentsflex.graph.data.GraphEdge;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final GraphImportTaskStore taskStore;
    private volatile boolean closed;

    /**
     * 使用单线程执行器创建服务。
     */
    public AsyncGraphImportService(GraphWriter writer) {
        this(writer, Executors.newSingleThreadExecutor(), new InMemoryGraphImportTaskStore());
    }

    /**
     * 使用调用方提供的执行器创建服务。
     */
    public AsyncGraphImportService(GraphWriter writer, ExecutorService executor) {
        this(writer, executor, new InMemoryGraphImportTaskStore());
    }

    /**
     * 使用自定义任务快照存储，便于接入持久化或分布式任务系统。
     */
    public AsyncGraphImportService(GraphWriter writer, ExecutorService executor, GraphImportTaskStore taskStore) {
        if (writer == null) throw new IllegalArgumentException("graph writer must not be null");
        if (executor == null) throw new IllegalArgumentException("executor must not be null");
        if (taskStore == null) throw new IllegalArgumentException("task store must not be null");
        this.writer = writer;
        this.executor = executor;
        this.taskStore = taskStore;
    }

    @Override
    public GraphImportTask submit(GraphImportRequest request, GraphOptions options) {
        if (request == null) throw new IllegalArgumentException("import request must not be null");
        if (closed) throw new IllegalStateException("import service is closed");
        State state = new State(UUID.randomUUID().toString(), System.currentTimeMillis());
        state.listener = request.getListener();
        state.checkpoint = request.getCheckpoint();
        state.source = request.getSource();
        state.nodesProcessed = request.getResumePoint().getNodesProcessed();
        state.edgesProcessed = request.getResumePoint().getEdgesProcessed();
        state.batchIndex = request.getResumePoint().getBatchesProcessed();
        tasks.put(state.id, state);
        persist(state);
        state.future = executor.submit(() -> run(state, request, options));
        return state.snapshot();
    }

    @Override
    public GraphImportTask get(String id) {
        if (id == null) return null;
        State state = tasks.get(id);
        return state == null ? taskStore.get(id) : state.snapshot();
    }

    @Override
    public List<GraphImportTask> list() {
        List<GraphImportTask> result = new ArrayList<>(taskStore.list());
        for (State state : tasks.values()) taskStore.save(state.snapshot());
        result = new ArrayList<>(taskStore.list());
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
            closeSource(state);
            persist(state);
            return true;
        }
    }

    @Override
    public boolean remove(String id) {
        State state = id == null ? null : tasks.get(id);
        if (state == null || !state.snapshot().isTerminal()) return false;
        boolean removed = tasks.remove(id, state);
        if (removed) taskStore.remove(id);
        return removed;
    }

    @Override
    public int purgeCompletedBefore(long epochMillis) {
        int removed = 0;
        for (Map.Entry<String, State> entry : tasks.entrySet()) {
            GraphImportTask task = entry.getValue().snapshot();
            if (task.isTerminal() && task.getCompletedAtMillis() >= 0
                && task.getCompletedAtMillis() < epochMillis
                && tasks.remove(entry.getKey(), entry.getValue())) {
                taskStore.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    private void run(State state, GraphImportRequest request, GraphOptions options) {
        synchronized (state) {
            if (state.cancelRequested) return;
            state.status = GraphImportStatus.RUNNING;
            state.startedAtMillis = System.currentTimeMillis();
            persist(state);
        }
        try {
            importNodes(state, request, options);
            importEdges(state, request, options);
            synchronized (state) {
                if (state.cancelRequested) return;
                state.status = state.report.isSuccess() ? GraphImportStatus.SUCCEEDED : GraphImportStatus.FAILED;
                state.message = state.report.isSuccess() ? "" : "Import completed with failed batches";
                state.completedAtMillis = System.currentTimeMillis();
                persist(state);
            }
        } catch (Throwable error) {
            synchronized (state) {
                if (state.cancelRequested) return;
                state.status = GraphImportStatus.FAILED;
                state.message = error.getMessage() == null ? error.getClass().getName() : error.getMessage();
                state.completedAtMillis = System.currentTimeMillis();
                persist(state);
                notifyError(state, error);
            }
        } finally {
            closeSource(state);
        }
    }

    private void importNodes(State state, GraphImportRequest request, GraphOptions options) {
        List<GraphNode> batch = new ArrayList<>();
        long skipped = 0L;
        for (GraphNode node : request.getNodes()) {
            checkCancelled(state);
            if (skipped++ < request.getResumePoint().getNodesProcessed()) continue;
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
        long skipped = 0L;
        for (GraphEdge edge : request.getEdges()) {
            checkCancelled(state);
            if (skipped++ < request.getResumePoint().getEdgesProcessed()) continue;
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
        state.report.add(state.batchIndex++, result);
        // checkpoint 只确认成功批次，失败批次必须允许调用方恢复时重新投递。
        if (result.isSuccess() && !mutation.getNodes().isEmpty()) state.nodesProcessed += mutation.getNodes().size();
        if (result.isSuccess() && !mutation.getEdges().isEmpty()) state.edgesProcessed += mutation.getEdges().size();
        if (result.isSuccess() && state.checkpoint != null) {
            try {
                state.checkpoint.onBatch(state.id, state.nodesProcessed, state.edgesProcessed);
            } catch (RuntimeException ignored) {
            }
        }
        persist(state);
        notifyBatch(state, result);
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
                    persist(state);
                }
                closeSource(state);
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
        private volatile GraphImportListener listener;
        private volatile GraphImportCheckpoint checkpoint;
        private volatile GraphImportSource source;
        private final AtomicBoolean sourceClosed = new AtomicBoolean();
        /**
         * 已成功确认的节点偏移；volatile 保证轮询线程可见。
         */
        private volatile long nodesProcessed;
        /**
         * 已成功确认的边偏移；volatile 保证轮询线程可见。
         */
        private volatile long edgesProcessed;
        /**
         * 批次序号；volatile 保证轮询线程可见。
         */
        private volatile int batchIndex;

        private State(String id, long submittedAtMillis) {
            this.id = id;
            this.submittedAtMillis = submittedAtMillis;
        }

        private GraphImportTask snapshot() {
            synchronized (this) {
                return new GraphImportTask(id, status, report, message, submittedAtMillis,
                    startedAtMillis, completedAtMillis, nodesProcessed, edgesProcessed, batchIndex);
            }
        }
    }

    /**
     * 幂等释放外部数据源，覆盖排队取消、执行取消和服务关闭路径。
     */
    private void closeSource(State state) {
        GraphImportSource source = state.source;
        if (source != null && state.sourceClosed.compareAndSet(false, true)) {
            try {
                source.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void persist(State state) {
        taskStore.save(state.snapshot());
        notifyProgress(state);
    }

    private void notifyProgress(State state) {
        if (state.listener != null) try {
            state.listener.onProgress(state.snapshot());
        } catch (RuntimeException ignored) {
        }
    }

    private void notifyBatch(State state, GraphWriteResult result) {
        if (state.listener != null) try {
            state.listener.onBatchCompleted(state.snapshot(), result);
        } catch (RuntimeException ignored) {
        }
    }

    private void notifyError(State state, Throwable error) {
        if (state.listener != null) try {
            state.listener.onError(state.snapshot(), error);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 仅用于快速退出工作线程，取消状态已经由 cancel/close 写入任务快照。
     */
    private static final class ImportCancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
