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
import static org.junit.Assert.assertTrue;
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

    /** 直接实现接口时应从泛型签名得到提交参数类型，不再要求编写重复的类型方法。 */
    @Test
    public void shouldResolveSubmitParamsTypeFromDirectImplementation() {
        DirectStringHandler handler = new DirectStringHandler("direct");

        assertEquals(String.class, handler.getSubmitParamsType());
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        assertSame(handler, registry.findBySubmitParamsType(String.class).get(0));
    }

    /** 泛型变量经过多个父类传递后，仍应替换为最终子类绑定的具体请求类型。 */
    @Test
    public void shouldResolveSubmitParamsTypeAcrossGenericHierarchy() {
        ConcreteStringHandler handler = new ConcreteStringHandler("hierarchy");

        assertEquals(String.class, handler.getSubmitParamsType());
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        assertSame(handler, registry.findBySubmitParamsType(String.class).get(0));
    }

    /** 业务可以先定义带泛型的领域 Handler 子接口，默认解析仍应找到最终绑定的请求类型。 */
    @Test
    public void shouldResolveSubmitParamsTypeAcrossGenericInterface() {
        InterfaceStringHandler handler = new InterfaceStringHandler("interface");

        assertEquals(String.class, handler.getSubmitParamsType());
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        assertSame(handler, registry.findBySubmitParamsType(String.class).get(0));
    }

    /** 未绑定类型变量不能静默退化为 Object；复杂代理或泛型实现可以显式覆盖类型方法。 */
    @Test
    public void shouldFailUnresolvedGenericTypeAndAllowExplicitOverride() {
        GenericHandler<String> unresolved = new GenericHandler<>("unresolved");
        IllegalStateException error = expect(IllegalStateException.class, unresolved::getSubmitParamsType);
        assertTrue(error.getMessage().contains("Override getSubmitParamsType()"));
        expect(IllegalStateException.class,
            () -> new InMemoryAsyncTaskHandlerRegistry().register(unresolved));

        GenericHandler<String> explicit = new GenericHandler<String>("explicit") {
            @Override
            public Class<String> getSubmitParamsType() {
                return String.class;
            }
        };
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(explicit);
        assertSame(explicit, registry.findBySubmitParamsType(String.class).get(0));
    }

    /** 参数化容器没有可用于精确路由的 Class，必须由 Handler 显式声明更具体的请求类型。 */
    @Test
    public void shouldRejectParameterizedSubmitParamsType() {
        expect(IllegalStateException.class, () -> new InMemoryAsyncTaskHandlerRegistry()
            .register(new ListHandler("list")));
    }

    private <T extends Throwable> T expect(Class<T> type, Runnable runnable) {
        try {
            runnable.run();
            fail("Expected " + type.getName());
        } catch (Throwable error) {
            if (!type.isInstance(error)) throw error;
            return type.cast(error);
        }
        throw new AssertionError("unreachable");
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

    /** 不覆盖 getSubmitParamsType，用于验证接口默认解析能力。 */
    private static final class DirectStringHandler implements AsyncTaskHandler<String> {
        private final String key;

        private DirectStringHandler(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public TaskSubmitResult submit(String params, TaskSubmitContext context) {
            return null;
        }

        @Override
        public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
            return null;
        }
    }

    private abstract static class GenericHandlerSupport<P> implements AsyncTaskHandler<P> {
        private final String key;

        private GenericHandlerSupport(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public TaskSubmitResult submit(P params, TaskSubmitContext context) {
            return null;
        }

        @Override
        public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
            return null;
        }
    }

    private abstract static class IntermediateHandler<P> extends GenericHandlerSupport<P> {
        private IntermediateHandler(String key) {
            super(key);
        }
    }

    private static final class ConcreteStringHandler extends IntermediateHandler<String> {
        private ConcreteStringHandler(String key) {
            super(key);
        }
    }

    private static class GenericHandler<P> extends GenericHandlerSupport<P> {
        private GenericHandler(String key) {
            super(key);
        }
    }

    private static final class ListHandler extends GenericHandlerSupport<List<String>> {
        private ListHandler(String key) {
            super(key);
        }
    }

    private interface DomainHandler<P> extends AsyncTaskHandler<P> {
    }

    private static final class InterfaceStringHandler implements DomainHandler<String> {
        private final String key;

        private InterfaceStringHandler(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public TaskSubmitResult submit(String params, TaskSubmitContext context) {
            return null;
        }

        @Override
        public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
            return null;
        }
    }
}
