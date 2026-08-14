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
package com.agentsflex.asynctask.handler;

import com.agentsflex.asynctask.support.AsyncTaskTestSupport;

import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * 验证 Handler 注册键唯一性、查找以及非法注册参数。
 */
public class AsyncTaskRegistryTest {
    @Test
    public void shouldRegisterAndResolveHandler() {
        AsyncTaskHandler<String> handler = AsyncTaskTestSupport.submittedHandler("handler");
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        assertSame(handler, registry.get("handler"));
    }

    @Test
    public void shouldRejectInvalidDuplicateAndMissingHandlers() {
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry();
        expect(IllegalArgumentException.class, () -> registry.register(null));
        expect(IllegalArgumentException.class,
            () -> registry.register(handler(null, String.class)));
        expect(IllegalArgumentException.class,
            () -> registry.register(handler(" ", String.class)));
        expect(IllegalArgumentException.class, () -> registry.register(new AsyncTaskHandler<Object>() {
            @Override public String getKey() { return "invalid"; }
            @Override public Class<Object> getSubmitParamsType() { return null; }
            @Override public com.agentsflex.asynctask.TaskSubmitResult submit(Object params,
                com.agentsflex.asynctask.TaskSubmitContext context) { return null; }
            @Override public com.agentsflex.asynctask.TaskQueryResult query(
                com.agentsflex.asynctask.TaskQueryParams params,
                com.agentsflex.asynctask.TaskQueryContext context) { return null; }
        }));
        registry.register(AsyncTaskTestSupport.submittedHandler("handler"));
        expect(IllegalStateException.class,
            () -> registry.register(AsyncTaskTestSupport.submittedHandler("handler")));
        expect(IllegalStateException.class, () -> registry.get("missing"));
    }

    /** 默认 Registry 实现只兼容按 key 恢复；使用自动路由前必须实现类型查询能力。 */
    @Test
    public void shouldRejectTypeLookupWhenRegistryDoesNotImplementIt() {
        AsyncTaskHandlerRegistry registry = key -> AsyncTaskTestSupport.submittedHandler(key);
        expect(UnsupportedOperationException.class,
            () -> registry.findBySubmitParamsType(String.class));
    }

    /**
     * 类型查询采用精确匹配并按 key 排序，确保选择器在不同 JVM 中看到相同的候选顺序。
     */
    @Test
    public void shouldFindExactTypeHandlersInStableOrder() {
        AsyncTaskHandler<String> second = AsyncTaskTestSupport.submittedHandler("b");
        AsyncTaskHandler<String> first = AsyncTaskTestSupport.submittedHandler("a");
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry()
            .register(second).register(first);

        List<AsyncTaskHandler<?>> handlers = registry.findBySubmitParamsType(String.class);
        assertEquals(2, handlers.size());
        assertSame(first, handlers.get(0));
        assertSame(second, handlers.get(1));
        assertEquals(0, registry.findBySubmitParamsType(Object.class).size());
        expect(UnsupportedOperationException.class, () -> handlers.add(first));
        expect(IllegalArgumentException.class, () -> registry.findBySubmitParamsType(null));
    }

    private void expect(Class<? extends Throwable> type, Runnable runnable) {
        try {
            runnable.run();
            fail("Expected " + type.getName());
        } catch (Throwable error) {
            if (!type.isInstance(error)) throw error;
        }
    }

    private <P> AsyncTaskHandler<P> handler(String key, Class<P> type) {
        return new AsyncTaskHandler<P>() {
            @Override public String getKey() { return key; }
            @Override public Class<P> getSubmitParamsType() { return type; }
            @Override public com.agentsflex.asynctask.TaskSubmitResult submit(P params,
                com.agentsflex.asynctask.TaskSubmitContext context) { return null; }
            @Override public com.agentsflex.asynctask.TaskQueryResult query(
                com.agentsflex.asynctask.TaskQueryParams params,
                com.agentsflex.asynctask.TaskQueryContext context) { return null; }
        };
    }
}
