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
package com.agentsflex.asynctask.policy;
import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import static org.junit.Assert.*;

/** 验证指数退避、最大延迟、抖动边界和非法构造参数。 */
public class ExponentialAsyncTaskRetryPolicyTest {
    @Test
    public void shouldUseFixedQueryAndBoundedExponentialErrorDelays() {
        ExponentialAsyncTaskRetryPolicy policy = new ExponentialAsyncTaskRetryPolicy(500, 100, 350, 3);
        AsyncTask task = new AsyncTask();
        assertEquals(500, policy.nextQueryDelayMillis(task, new TaskQueryResult()));
        task.setConsecutiveErrors(1);
        assertEquals(100, policy.nextErrorDelayMillis(task, new RuntimeException()));
        task.setConsecutiveErrors(2);
        assertEquals(200, policy.nextErrorDelayMillis(task, new RuntimeException()));
        task.setConsecutiveErrors(3);
        assertEquals(350, policy.nextErrorDelayMillis(task, new RuntimeException()));
        task.setConsecutiveErrors(30);
        assertEquals(350, policy.nextErrorDelayMillis(task, new RuntimeException()));
    }

    @Test
    public void shouldStopAfterConfiguredConsecutiveErrors() {
        ExponentialAsyncTaskRetryPolicy policy = new ExponentialAsyncTaskRetryPolicy(1, 1, 10, 2);
        AsyncTask task = new AsyncTask();
        task.setConsecutiveErrors(2);
        assertTrue(policy.shouldRetry(task, new RuntimeException()));
        task.setConsecutiveErrors(3);
        assertFalse(policy.shouldRetry(task, new RuntimeException()));
    }

    @Test
    public void shouldValidateConfigurationAndAvoidOverflow() {
        expect(() -> new ExponentialAsyncTaskRetryPolicy(0, 1, 1, 1));
        expect(() -> new ExponentialAsyncTaskRetryPolicy(1, 0, 1, 1));
        expect(() -> new ExponentialAsyncTaskRetryPolicy(1, 1, 0, 1));
        expect(() -> new ExponentialAsyncTaskRetryPolicy(1, 1, 1, -1));
        ExponentialAsyncTaskRetryPolicy policy =
            new ExponentialAsyncTaskRetryPolicy(1, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        AsyncTask task = new AsyncTask();
        task.setConsecutiveErrors(20);
        assertEquals(Long.MAX_VALUE, policy.nextErrorDelayMillis(task, new RuntimeException()));
    }

    private void expect(Runnable runnable) {
        try { runnable.run(); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
