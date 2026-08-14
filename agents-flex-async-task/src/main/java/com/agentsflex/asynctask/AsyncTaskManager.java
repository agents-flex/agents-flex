/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.asynctask;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;
import com.agentsflex.asynctask.handler.AsyncTaskHandlerRegistry;
import com.agentsflex.asynctask.handler.selector.AsyncTaskHandlerSelectionContext;
import com.agentsflex.asynctask.handler.selector.AsyncTaskHandlerSelector;
import com.agentsflex.asynctask.store.AsyncTaskStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务的应用入口，负责校验并持久化待提交任务。
 *
 * <p>Manager 不直接访问供应商。所有任务都会先进入 {@link AsyncTaskStatus#PENDING_SUBMIT}，
 * 再由 Worker 统一执行供应商提交、准入控制、状态查询和重试。</p>
 */
public final class AsyncTaskManager {
    private final AsyncTaskStore store;
    private final AsyncTaskHandlerRegistry registry;
    private final AsyncTaskHandlerSelector handlerSelector;

    /**
     * 创建任务管理器。
     *
     * @param store    任务持久化与权威时钟来源
     * @param registry 支持按参数类型选择、按持久化 handlerKey 恢复的 Handler 注册表
     */
    public AsyncTaskManager(AsyncTaskStore store, AsyncTaskHandlerRegistry registry) {
        this(store, registry, null);
    }

    /**
     * 创建带多 Handler 路由能力的任务管理器。
     *
     * <p>selector 仅在同一提交参数类型匹配到多个 Handler，且 options.handlerKey 未指定时调用。
     * 每种参数只有一个 Handler 的应用无需配置 selector；多个候选却未配置时会抛出明确异常，避免路由
     * 因注册表变化而静默改变。</p>
     *
     * @param store           任务持久化与权威时钟来源
     * @param registry        Handler 注册表
     * @param handlerSelector 多候选选择器，可以为空
     */
    public AsyncTaskManager(AsyncTaskStore store, AsyncTaskHandlerRegistry registry,
                            AsyncTaskHandlerSelector handlerSelector) {
        if (store == null || registry == null) throw new IllegalArgumentException("store and registry are required");
        this.store = store;
        this.registry = registry;
        this.handlerSelector = handlerSelector;
    }

    /**
     * 根据提交参数类型自动选择 Handler，并使用默认调度选项创建持久化异步任务。
     *
     * @param params                能够持久化并在其他 Worker 恢复的提交参数
     * @param trackingTimeoutMillis 包含排队、提交和查询阶段的总跟踪时限
     * @return 状态为 PENDING_SUBMIT 的任务快照
     */
    public <P> AsyncTask submit(P params, long trackingTimeoutMillis) {
        return submit(params, trackingTimeoutMillis, null);
    }

    /**
     * 根据参数类型选择 Handler 并创建持久化异步任务。
     *
     * <p>参数校验发生在 Store 写入之前。Handler 可以声明领域约束，Manager 还会拒绝本地文件、流、
     * 字节数组和无法完成 Java 序列化的对象，避免创建永远不能在其他节点恢复的任务。options.handlerKey
     * 可强制使用指定 Handler；否则按精确参数类型查找，唯一候选直接使用，多候选交给 Manager selector。</p>
     *
     * @param params                Handler 接受且适合持久化的提交参数
     * @param trackingTimeoutMillis 从创建时刻起计算的总跟踪时限，必须大于 0
     * @param options               Handler 路由、调度、隔离和 metadata；为空时使用默认值
     * @return 已写入 Store 的 PENDING_SUBMIT 任务
     */
    public <P> AsyncTask submit(P params, long trackingTimeoutMillis, AsyncTaskOptions options) {
        if (trackingTimeoutMillis <= 0)
            throw new IllegalArgumentException("trackingTimeoutMillis must be greater than 0");
        if (params == null) throw new IllegalArgumentException("submit params are required");

        AsyncTaskOptions effective = options == null ? new AsyncTaskOptions() : options;
        AsyncTaskHandler<?> handler = selectHandler(params, effective);
        String handlerKey = handler.getKey();
        validateHandlerParams(handler, params);
        validatePersistentValue("submit params", params);
        validatePersistentValue("metadata", effective.getMetadata());

        long now = store.currentTimeMillis();
        AsyncTask task = new AsyncTask();
        task.setId(UUID.randomUUID().toString());
        task.setHandlerKey(handlerKey);
        task.setSubmitParams(params);
        task.setProviderKey(hasText(effective.getProviderKey()) ? effective.getProviderKey() : handlerKey);
        task.setAccountId(effective.getAccountId());
        task.setTenantId(effective.getTenantId());
        task.setPriority(effective.getPriority());
        task.setScheduledSubmitAt(safeAdd(now, effective.getDelayMillis()));
        task.setStatus(AsyncTaskStatus.PENDING_SUBMIT);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeadlineAt(safeAdd(now, trackingTimeoutMillis));
        task.setMetadata(effective.getMetadata());
        return store.create(task);
    }

    /**
     * 获取 Store 中的最新任务快照；不存在时返回 null。
     */
    public AsyncTask get(String taskId) {
        return store.load(taskId);
    }

    /**
     * 请求取消任务；返回 false 表示不存在、已终态或已请求过取消。
     */
    public boolean cancel(String taskId) {
        return store.requestCancellation(taskId);
    }

    @SuppressWarnings("unchecked")
    private <P> void validateHandlerParams(AsyncTaskHandler<?> handler, P params) {
        ((AsyncTaskHandler<P>) handler).validateSubmitParams(params);
    }

    private AsyncTaskHandler<?> selectHandler(Object params, AsyncTaskOptions options) {
        if (hasText(options.getHandlerKey())) {
            AsyncTaskHandler<?> specified = registry.get(options.getHandlerKey());
            if (!specified.getSubmitParamsType().isInstance(params)) {
                throw new IllegalArgumentException("Handler " + specified.getKey() + " requires submit params of type "
                    + specified.getSubmitParamsType().getName() + ", but received " + params.getClass().getName());
            }
            return specified;
        }

        List<AsyncTaskHandler<?>> candidates = registry.findBySubmitParamsType(params.getClass());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No async task handler registered for submit params type: "
                + params.getClass().getName());
        }
        if (candidates.size() == 1) return candidates.get(0);
        if (handlerSelector == null) {
            throw new IllegalStateException("Multiple async task handlers registered for submit params type "
                + params.getClass().getName() + ": " + handlerKeys(candidates)
                + ". Configure an AsyncTaskHandlerSelector or set AsyncTaskOptions.handlerKey.");
        }

        AsyncTaskHandler<?> selected = handlerSelector.select(
            new AsyncTaskHandlerSelectionContext(params, options, candidates));
        if (selected == null || !candidates.contains(selected)) {
            throw new IllegalStateException("AsyncTaskHandlerSelector must return one of the candidate handlers: "
                + handlerKeys(candidates));
        }
        return selected;
    }

    private String handlerKeys(List<AsyncTaskHandler<?>> handlers) {
        StringBuilder keys = new StringBuilder();
        for (AsyncTaskHandler<?> handler : handlers) {
            if (keys.length() > 0) keys.append(", ");
            keys.append(handler.getKey());
        }
        return keys.toString();
    }

    private void validatePersistentValue(String field, Object value) {
        Object unsupported = findUnsupportedValue(value, new IdentityHashMap<>());
        if (unsupported != null) {
            throw new IllegalArgumentException("Persistent async task " + field + " does not support "
                + unsupported.getClass().getSimpleName()
                + ". Upload file or binary content to storage and submit an accessible URL instead.");
        }
        if (!(value instanceof Serializable)) {
            throw new IllegalArgumentException("Persistent async task " + field + " must implement Serializable: "
                + value.getClass().getName());
        }
        try {
            ObjectOutputStream output = new ObjectOutputStream(new ByteArrayOutputStream());
            output.writeObject(value);
            output.close();
        } catch (Exception error) {
            throw new IllegalArgumentException("Persistent async task " + field
                + " contains a non-serializable value: " + value.getClass().getName(), error);
        }
    }

    private Object findUnsupportedValue(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null || isLeaf(value.getClass())) return null;
        if (value instanceof File || value instanceof InputStream || value instanceof OutputStream
            || value instanceof Reader || value instanceof Writer || value instanceof byte[]) return value;
        if (visited.put(value, Boolean.TRUE) != null) return null;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                Object found = findUnsupportedValue(Array.get(value, i), visited);
                if (found != null) return found;
            }
            return null;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                Object found = findUnsupportedValue(item, visited);
                if (found != null) return found;
            }
            return null;
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object found = findUnsupportedValue(entry.getKey(), visited);
                if (found == null) found = findUnsupportedValue(entry.getValue(), visited);
                if (found != null) return found;
            }
            return null;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (current.getName().startsWith("java.")) continue;
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object found = findUnsupportedValue(field.get(value), visited);
                    if (found != null) return found;
                } catch (RuntimeException | IllegalAccessException ignored) {
                    // 无法反射访问的字段仍会由后续真实序列化校验兜底。
                }
            }
        }
        return null;
    }

    private boolean isLeaf(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || Number.class.isAssignableFrom(type)
            || CharSequence.class.isAssignableFrom(type) || Boolean.class == type || Character.class == type
            || UUID.class == type || type.getName().startsWith("java.time.");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private long safeAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}
