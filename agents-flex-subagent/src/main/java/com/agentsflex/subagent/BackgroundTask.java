package com.agentsflex.subagent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages the execution of a background task using CompletableFuture. This class
 * provides thread-safe access to task status, results, and error information while
 * leveraging Java's modern concurrency utilities.
 *
 * <p>
 * Tasks are automatically started upon construction using the provided ExecutorService.
 * Callers can check completion status, wait for completion, cancel tasks, and retrieve
 * results or errors through the public API.
 * </p>
 *
 * @author Christian Tzolov
 * @author Michael Yang
 */
public class BackgroundTask {

    private final String taskId;

    private final CompletableFuture<String> future;

    /**
     * Internal constructor for creating a BackgroundTask with an existing future.
     * @param taskId the task identifier
     * @param future the completable future to wrap
     */
    public BackgroundTask(String taskId, CompletableFuture<String> future) {
        this.taskId = taskId;
        this.future = future;
    }

    /**
     * Check if the task has completed execution.
     * @return true if the task has completed (successfully or with error), false
     * otherwise
     */
    public boolean isCompleted() {
        return this.future.isDone();
    }

    /**
     * Set the result of the task execution. This completes the future with the given
     * result.
     * @param result the result to set
     */
    public void setResult(String result) {
        this.future.complete(result);
    }

    /**
     * Get the result of the task execution. This method blocks until the result is
     * available.
     * @return the task result, or null if not yet completed or if an error occurred
     */
    public String getResult() {
        try {
            return this.future.getNow(null);
        }
        catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the error that occurred during task execution, if any.
     * @return the exception that occurred, or null if no error
     */
    public Exception getError() {
        if (this.future.isCompletedExceptionally()) {
            try {
                this.future.getNow(null);
            }
            catch (Exception e) {
                // Unwrap CompletionException if present
                if (e.getCause() instanceof Exception) {
                    return (Exception) e.getCause();
                }
                return e;
            }
        }
        return null;
    }

    /**
     * Get the error message if an error occurred.
     * @return the error message, or null if no error
     */
    public String getErrorMessage() {
        Exception error = getError();
        return error != null ? error.getMessage() : null;
    }

    /**
     * Check if the task has an error.
     * @return true if an error occurred, false otherwise
     */
    public boolean hasError() {
        return this.future.isCompletedExceptionally();
    }

    /**
     * Get a human-readable status description of the task.
     * @return status string: "Running", "Completed", or "Failed: [error message]"
     */
    public String getStatus() {
        if (this.future.isCompletedExceptionally()) {
            Exception error = getError();
            return "Failed: " + (error != null ? error.getMessage() : "Unknown error");
        }
        return this.future.isDone() ? "Completed" : "Running";
    }

    /**
     * Wait for the task to complete within the specified timeout.
     * @param timeoutMs the maximum time to wait in milliseconds
     * @return true if the task completed within the timeout, false if it timed out
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean waitForCompletion(long timeoutMs) throws InterruptedException {
        if (this.future.isDone()) {
            return true;
        }
        try {
            this.future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        }
        catch (InterruptedException e) {
            // Re-throw InterruptedException to preserve interrupt semantics
            throw e;
        }
        catch (TimeoutException e) {
            return false;
        }
        catch (Exception e) {
            // Task completed with exception (ExecutionException), still considered
            // "completed"
            return true;
        }
    }

    /**
     * Cancel the task if it hasn't completed yet.
     * @param mayInterruptIfRunning true if the thread executing the task should be
     * interrupted
     * @return true if the task was cancelled, false if it was already completed
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        return this.future.cancel(mayInterruptIfRunning);
    }

    /**
     * Check if the task was cancelled.
     * @return true if the task was cancelled before completion
     */
    public boolean isCancelled() {
        return this.future.isCancelled();
    }

    /**
     * Get the task ID.
     * @return the task ID
     */
    public String getTaskId() {
        return this.taskId;
    }

    /**
     * Get the underlying CompletableFuture for advanced operations.
     * @return the CompletableFuture backing this task
     */
    public CompletableFuture<String> getFuture() {
        return this.future;
    }

}
