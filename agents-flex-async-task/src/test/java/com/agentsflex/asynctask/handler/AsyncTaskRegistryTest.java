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
        registry.register(AsyncTaskTestSupport.submittedHandler("handler"));
        expect(IllegalStateException.class,
            () -> registry.register(AsyncTaskTestSupport.submittedHandler("handler")));
        expect(IllegalStateException.class, () -> registry.get("missing"));
    }

    private void expect(Class<? extends Throwable> type, Runnable runnable) {
        try {
            runnable.run();
            fail("Expected " + type.getName());
        } catch (Throwable error) {
            if (!type.isInstance(error)) throw error;
        }
    }
}
