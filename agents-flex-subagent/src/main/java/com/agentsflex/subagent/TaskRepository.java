/*
 * Copyright 2025 - 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentsflex.subagent;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Default implementation of TaskRepository that manages background tasks using a thread
 * pool.
 *
 * @author Christian Tzolov
 * @author Michael Yang
 */
public class TaskRepository {

    private final Map<String, BackgroundTask> backgroundTasks = new ConcurrentHashMap<>();

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    /**
     * Creates a repository with a default cached thread pool executor.
     */
    public TaskRepository() {
        this(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("background-task-" + thread.getId());
            return thread;
        }), true);
    }

    /**
     * Creates a repository with a custom executor service.
     *
     * @param executor the executor service to use for running tasks
     */
    public TaskRepository(ExecutorService executor) {
        this(executor, false);
    }

    /**
     * Internal constructor for specifying executor ownership.
     *
     * @param executor     the executor service
     * @param ownsExecutor whether this repository owns the executor and should shut it
     *                     down
     */
    public TaskRepository(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    public BackgroundTask getTasks(String taskId) {
        return this.backgroundTasks.get(taskId);
    }

    public BackgroundTask putTask(String taskId, Supplier<String> taskExecution) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(taskExecution, this.executor);
        BackgroundTask backgroundTask = new BackgroundTask(taskId, future);
        this.backgroundTasks.put(taskId, backgroundTask);
        return backgroundTask;
    }

    public void removeTask(String taskId) {
        this.backgroundTasks.remove(taskId);
    }

    public void clear() {
        this.backgroundTasks.clear();
    }


    public void clearCompletedTasks() {
        this.backgroundTasks.entrySet().removeIf(entry -> entry.getValue().isCompleted());
    }

    /**
     * Shutdown the executor service if this repository owns it. This method should be
     * called when the repository is no longer needed to ensure proper cleanup of threads.
     */
    public void shutdown() {
        if (this.ownsExecutor && this.executor != null) {
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

}
