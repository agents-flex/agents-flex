/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;
import com.agentsflex.asynctask.handler.InMemoryAsyncTaskHandlerRegistry;
import com.agentsflex.asynctask.handler.selector.AsyncTaskHandlerSelector;
import com.agentsflex.asynctask.store.InMemoryAsyncTaskStore;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 验证 Manager 统一入队、参数持久化校验、调度选项、查询和取消契约。
 */
public class AsyncTaskManagerTest {
    /**
     * 公开 API 必须只保留以 request 开头的新 submit 形态，防止旧 handlerKey 参数被重新引入。
     */
    @Test
    public void shouldExposeRequestFirstSubmitApiOnly() {
        List<List<Class<?>>> signatures = new ArrayList<>();
        for (Method method : AsyncTaskManager.class.getDeclaredMethods()) {
            if (method.getName().equals("submit") && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                signatures.add(Arrays.asList(method.getParameterTypes()));
            }
        }

        assertEquals(2, signatures.size());
        assertTrue(signatures.contains(Arrays.<Class<?>>asList(Object.class, long.class)));
        assertTrue(signatures.contains(Arrays.<Class<?>>asList(
            Object.class, long.class, AsyncTaskOptions.class)));
        for (List<Class<?>> signature : signatures) assertNotEquals(String.class, signature.get(0));
    }

    @Test
    public void shouldPersistPendingTaskWithoutCallingProvider() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        RecordingHandler handler = new RecordingHandler();
        AsyncTaskOptions options = new AsyncTaskOptions();
        options.setProviderKey("provider");
        options.setAccountId("account");
        options.setTenantId("tenant");
        options.setPriority(8);
        options.setDelayMillis(500);
        options.setMetadata(Collections.<String, Object>singletonMap("trace", "t1"));

        PersistedRequest request = new PersistedRequest("request");
        AsyncTask task = manager(store, handler).submit(request, 60_000, options);

        assertEquals(AsyncTaskStatus.PENDING_SUBMIT, task.getStatus());
        assertSame(request, task.getSubmitParams());
        assertEquals("provider", task.getProviderKey());
        assertEquals("account", task.getAccountId());
        assertEquals("tenant", task.getTenantId());
        assertEquals(8, task.getPriority());
        assertTrue(task.getScheduledSubmitAt() >= task.getCreatedAt() + 500);
        assertEquals("t1", task.getMetadata().get("trace"));
        assertEquals(0, handler.submitCalls);
        assertEquals(task.getId(), store.load(task.getId()).getId());
    }

    @Test
    public void shouldUseHandlerKeyAndDefaultOptions() {
        AsyncTask task = manager(new InMemoryAsyncTaskStore(), new RecordingHandler())
            .submit(new PersistedRequest("request"), Long.MAX_VALUE);
        assertEquals("manager", task.getProviderKey());
        assertEquals(task.getCreatedAt(), task.getScheduledSubmitAt());
        assertEquals(Long.MAX_VALUE, task.getDeadlineAt());
    }

    @Test
    public void shouldValidateHandlerBeforeCreatingTask() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        RecordingHandler handler = new RecordingHandler();
        handler.validationError = "blocked";

        IllegalArgumentException error = expect(() ->
            manager(store, handler).submit(new PersistedRequest("request"), 1000));

        assertEquals("blocked", error.getMessage());
        assertEquals(1, handler.validationCalls);
        assertTrue(store.claimDueSubmissions("worker", store.currentTimeMillis(), 1000, 10,
            (task, tasks, now) -> true).isEmpty());
    }

    @Test
    public void shouldRejectUnsupportedPersistentValuesBeforeCreatingTask() {
        assertUnsupported(new FileRequest(new File("document.pdf")), "File");
        assertUnsupported(new BytesRequest(new byte[]{1, 2}), "byte[]");
        assertUnsupported(new StreamRequest(new ByteArrayInputStream(new byte[]{1})), "ByteArrayInputStream");
        assertUnsupported(new ArrayRequest(new Object[]{"safe", new File("document.pdf")}), "File");
        assertUnsupported(new IterableRequest(Arrays.<Object>asList("safe", new File("document.pdf"))), "File");

        // 数组和集合只包含可持久化值时应正常创建，不能因为容器类型被误拒绝。
        assertEquals(AsyncTaskStatus.PENDING_SUBMIT,
            manager(new InMemoryAsyncTaskStore(), handlerFor(ArrayRequest.class))
                .submit(new ArrayRequest(new Object[]{"safe", 1}), 1000).getStatus());
        assertEquals(AsyncTaskStatus.PENDING_SUBMIT,
            manager(new InMemoryAsyncTaskStore(), handlerFor(IterableRequest.class))
                .submit(new IterableRequest(Arrays.<Object>asList("safe", 1)), 1000).getStatus());

        IllegalArgumentException nonSerializable = expect(() -> manager(new InMemoryAsyncTaskStore(),
            handlerFor(NonSerializableRequest.class)).submit(new NonSerializableRequest(), 1000));
        assertTrue(nonSerializable.getMessage().contains("Serializable"));
    }

    /**
     * metadata 与提交参数会一起进入 Store，也必须在创建任务前完成同等级别的持久化校验。
     */
    @Test
    public void shouldRejectUnsupportedMetadataBeforeCreatingTask() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTaskManager manager = manager(store, new RecordingHandler());
        AsyncTaskOptions options = new AsyncTaskOptions();
        options.setMetadata(Collections.<String, Object>singletonMap("content", new byte[]{1, 2}));

        IllegalArgumentException binary = expect(() ->
            manager.submit(new PersistedRequest("request"), 1000, options));
        assertTrue(binary.getMessage(), binary.getMessage().contains("metadata"));
        assertTrue(binary.getMessage(), binary.getMessage().contains("byte[]"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("client", new Object());
        options.setMetadata(metadata);
        IllegalArgumentException nonSerializable = expect(() ->
            manager.submit(new PersistedRequest("request"), 1000, options));
        assertTrue(nonSerializable.getMessage(), nonSerializable.getMessage().contains("metadata"));

        // 两次校验均在 store.create() 之前失败，不能留下无法恢复的半成品任务。
        assertTrue(store.claimDueSubmissions("worker", store.currentTimeMillis(), 1000, 10,
            (task, tasks, now) -> true).isEmpty());
    }

    @Test
    public void shouldRejectInvalidInputAndDelegateLookupAndCancellation() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTaskManager manager = manager(store, new RecordingHandler());
        expect(() -> manager.submit(new PersistedRequest("x"), 0));
        expect(() -> manager.submit(null, 100));
        expect(() -> new AsyncTaskManager(null, new InMemoryAsyncTaskHandlerRegistry()));
        expect(() -> new AsyncTaskManager(store, null));

        AsyncTask task = manager.submit(new PersistedRequest("request"), 1000);
        assertEquals(task.getId(), manager.get(task.getId()).getId());
        assertTrue(manager.cancel(task.getId()));
        assertFalse(manager.cancel(task.getId()));
        assertFalse(manager.cancel("missing"));
    }

    /**
     * 单一候选是最常见用法，调用方不再需要了解或传递 Handler Key。
     */
    @Test
    public void shouldSelectOnlyHandlerByRequestType() {
        RecordingHandler handler = new RecordingHandler();
        int[] selectorCalls = {0};
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        AsyncTask task = new AsyncTaskManager(new InMemoryAsyncTaskStore(), registry, context -> {
            selectorCalls[0]++;
            return handler;
        })
            .submit(new PersistedRequest("request"), 1000);

        assertEquals("manager", task.getHandlerKey());
        assertEquals("manager", task.getProviderKey());
        assertEquals("唯一候选不能调用 selector", 0, selectorCalls[0]);
    }

    /**
     * 多候选时 selector 能看到请求、选项和稳定排序后的候选，选择结果随后写入任务快照。
     */
    @Test
    public void shouldSelectFromMultipleHandlersWithCompleteContext() {
        AsyncTaskHandler<PersistedRequest> second = keyedHandler("provider:b", PersistedRequest.class);
        AsyncTaskHandler<PersistedRequest> first = keyedHandler("provider:a", PersistedRequest.class);
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry()
            .register(second).register(first);
        AsyncTaskOptions options = new AsyncTaskOptions();
        options.setTenantId("tenant-1");
        PersistedRequest request = new PersistedRequest("request");
        int[] selectorCalls = {0};

        AsyncTask task = new AsyncTaskManager(new InMemoryAsyncTaskStore(), registry, context -> {
            selectorCalls[0]++;
            assertSame(request, context.getRequest());
            assertNotSame(options, context.getOptions());
            assertEquals("tenant-1", context.getOptions().getTenantId());
            context.getOptions().setTenantId("selector-mutated");
            assertEquals(Arrays.asList(first, second), context.getCandidates());
            return second;
        }).submit(request, 1000, options);

        assertEquals(1, selectorCalls[0]);
        assertEquals("provider:b", task.getHandlerKey());
        assertEquals("provider:b", task.getProviderKey());
        assertEquals("tenant-1", task.getTenantId());
    }

    /**
     * 自动匹配只接受精确 request 类型；空白显式 key 等同未指定，不能绕过该规则。
     */
    @Test
    public void shouldUseExactRequestTypeAndIgnoreBlankExplicitKey() {
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry()
            .register(keyedHandler("base", BaseRequest.class))
            .register(keyedHandler("child", ChildRequest.class));
        AsyncTaskOptions options = new AsyncTaskOptions();
        options.setHandlerKey(" ");

        AsyncTask child = new AsyncTaskManager(new InMemoryAsyncTaskStore(), registry)
            .submit(new ChildRequest(), 1000, options);
        assertEquals("child", child.getHandlerKey());

        InMemoryAsyncTaskHandlerRegistry baseOnly = new InMemoryAsyncTaskHandlerRegistry()
            .register(keyedHandler("base", BaseRequest.class));
        IllegalStateException error = expectState(() -> new AsyncTaskManager(
            new InMemoryAsyncTaskStore(), baseOnly).submit(new ChildRequest(), 1000));
        assertTrue(error.getMessage().contains(ChildRequest.class.getName()));
    }

    /** 自定义 Registry 若未实现类型查找，新 API 应明确暴露能力缺失，而不是误选 Handler。 */
    @Test
    public void shouldRequireRegistryTypeLookupCapability() {
        com.agentsflex.asynctask.handler.AsyncTaskHandlerRegistry registry = key ->
            keyedHandler(key, PersistedRequest.class);
        expect(UnsupportedOperationException.class, () -> new AsyncTaskManager(
            new InMemoryAsyncTaskStore(), registry).submit(new PersistedRequest("request"), 1000));
    }

    /**
     * 同一请求类型存在多个供应商实现时，不配置选择器必须失败，避免注册新 Handler 后静默改变路由。
     */
    @Test
    public void shouldRejectMissingAndAmbiguousHandlerSelection() {
        InMemoryAsyncTaskHandlerRegistry empty = new InMemoryAsyncTaskHandlerRegistry();
        IllegalStateException missing = expectState(() -> new AsyncTaskManager(
            new InMemoryAsyncTaskStore(), empty).submit(new PersistedRequest("request"), 1000));
        assertTrue(missing.getMessage().contains(PersistedRequest.class.getName()));

        InMemoryAsyncTaskHandlerRegistry multiple = new InMemoryAsyncTaskHandlerRegistry()
            .register(keyedHandler("provider:a", PersistedRequest.class))
            .register(keyedHandler("provider:b", PersistedRequest.class));
        IllegalStateException ambiguous = expectState(() -> new AsyncTaskManager(
            new InMemoryAsyncTaskStore(), multiple).submit(new PersistedRequest("request"), 1000));
        assertTrue(ambiguous.getMessage().contains("provider:a, provider:b"));
        assertTrue(ambiguous.getMessage().contains("AsyncTaskHandlerSelector"));
    }

    /**
     * options.handlerKey 是一次请求的强制路由，优先级高于 Manager 选择器，且结果必须固化进任务。
     */
    @Test
    public void shouldPreferExplicitHandlerKeyAndPersistSelection() {
        AsyncTaskHandler<PersistedRequest> first = keyedHandler("provider:a", PersistedRequest.class);
        AsyncTaskHandler<PersistedRequest> second = keyedHandler("provider:b", PersistedRequest.class);
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry()
            .register(first).register(second);
        int[] selectorCalls = {0};
        AsyncTaskHandlerSelector selector = context -> {
            selectorCalls[0]++;
            return first;
        };
        AsyncTaskOptions options = new AsyncTaskOptions();
        options.setHandlerKey("provider:b");

        AsyncTask task = new AsyncTaskManager(new InMemoryAsyncTaskStore(), registry, selector)
            .submit(new PersistedRequest("request"), 1000, options);

        assertEquals("provider:b", task.getHandlerKey());
        assertEquals("provider:b", task.getProviderKey());
        assertEquals(0, selectorCalls[0]);
    }

    /**
     * 强制路由是严格约束：key 不存在、类型不兼容时都不能降级到其他候选。
     */
    @Test
    public void shouldRejectInvalidExplicitHandlerKey() {
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry()
            .register(keyedHandler("request", PersistedRequest.class))
            .register(keyedHandler("string", String.class));
        AsyncTaskManager manager = new AsyncTaskManager(new InMemoryAsyncTaskStore(), registry);
        AsyncTaskOptions options = new AsyncTaskOptions();

        options.setHandlerKey("missing");
        expectState(() -> manager.submit(new PersistedRequest("request"), 1000, options));

        options.setHandlerKey("string");
        IllegalArgumentException mismatch = expect(() ->
            manager.submit(new PersistedRequest("request"), 1000, options));
        assertTrue(mismatch.getMessage().contains(PersistedRequest.class.getName()));
    }

    /**
     * Manager 只接受候选集合中的返回值，防止错误选择器绕过请求类型约束。
     */
    @Test
    public void shouldValidateSelectorResult() {
        AsyncTaskHandler<PersistedRequest> first = keyedHandler("a", PersistedRequest.class);
        AsyncTaskHandler<PersistedRequest> second = keyedHandler("b", PersistedRequest.class);
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry()
            .register(first).register(second);

        expectState(() -> new AsyncTaskManager(new InMemoryAsyncTaskStore(), registry, context -> null)
            .submit(new PersistedRequest("request"), 1000));
        expectState(() -> new AsyncTaskManager(new InMemoryAsyncTaskStore(), registry,
            context -> keyedHandler("outside", PersistedRequest.class))
            .submit(new PersistedRequest("request"), 1000));
    }

    /** Selector 自身失败时异常应原样返回，并且 Store 中不能留下未完成的任务。 */
    @Test
    public void shouldNotCreateTaskWhenSelectorFails() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry()
            .register(keyedHandler("a", PersistedRequest.class))
            .register(keyedHandler("b", PersistedRequest.class));
        IllegalStateException expected = new IllegalStateException("routing unavailable");

        try {
            new AsyncTaskManager(store, registry, context -> { throw expected; })
                .submit(new PersistedRequest("request"), 1000);
            fail("Expected selector failure");
        } catch (IllegalStateException actual) {
            assertSame(expected, actual);
        }
        assertTrue(store.claimDueSubmissions("worker", store.currentTimeMillis(), 1000, 10,
            (task, tasks, now) -> true).isEmpty());
    }

    private void assertUnsupported(Serializable request, String type) {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        IllegalArgumentException error = expect(() ->
            new AsyncTaskManager(store, new InMemoryAsyncTaskHandlerRegistry().register(
                handlerFor((Class) request.getClass()))).submit(request, 1000));
        assertTrue(error.getMessage(), error.getMessage().contains(type));
    }

    private AsyncTaskManager manager(InMemoryAsyncTaskStore store, AsyncTaskHandler<?> handler) {
        return new AsyncTaskManager(store, new InMemoryAsyncTaskHandlerRegistry().register(handler));
    }

    private <P> AsyncTaskHandler<P> handlerFor(Class<P> type) {
        return keyedHandler("typed", type);
    }

    private <P> AsyncTaskHandler<P> keyedHandler(String key, Class<P> type) {
        return new AsyncTaskHandler<P>() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public Class<P> getSubmitParamsType() {
                return type;
            }

            @Override
            public TaskSubmitResult submit(P params, TaskSubmitContext context) {
                return null;
            }

            @Override
            public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                return null;
            }
        };
    }

    private IllegalArgumentException expect(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
            return null;
        } catch (IllegalArgumentException expected) {
            return expected;
        }
    }

    private void expect(Class<? extends Throwable> type, Runnable runnable) {
        try { runnable.run(); fail("Expected " + type.getName()); }
        catch (Throwable expected) {
            if (!type.isInstance(expected)) throw expected;
        }
    }

    private IllegalStateException expectState(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalStateException");
            return null;
        } catch (IllegalStateException expected) {
            return expected;
        }
    }

    private static final class RecordingHandler implements AsyncTaskHandler<PersistedRequest> {
        private int validationCalls;
        private int submitCalls;
        private String validationError;

        @Override
        public String getKey() {
            return "manager";
        }

        @Override
        public Class<PersistedRequest> getSubmitParamsType() {
            return PersistedRequest.class;
        }

        @Override
        public void validateSubmitParams(PersistedRequest params) {
            validationCalls++;
            if (validationError != null) throw new IllegalArgumentException(validationError);
        }

        @Override
        public TaskSubmitResult submit(PersistedRequest params, TaskSubmitContext context) {
            submitCalls++;
            return null;
        }

        @Override
        public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
            return null;
        }
    }

    private static final class PersistedRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String value;

        private PersistedRequest(String value) {
            this.value = value;
        }
    }

    private static final class FileRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final File value;

        private FileRequest(File value) {
            this.value = value;
        }
    }

    private static final class BytesRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final byte[] value;

        private BytesRequest(byte[] value) {
            this.value = value;
        }
    }

    private static final class StreamRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Object value;

        private StreamRequest(Object value) {
            this.value = value;
        }
    }

    private static final class ArrayRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Object[] value;

        private ArrayRequest(Object[] value) {
            this.value = value;
        }
    }

    private static final class IterableRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final java.util.List<Object> value;

        private IterableRequest(java.util.List<Object> value) {
            this.value = value;
        }
    }

    private static final class NonSerializableRequest {
    }

    private static class BaseRequest implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private static final class ChildRequest extends BaseRequest {
        private static final long serialVersionUID = 1L;
    }
}
