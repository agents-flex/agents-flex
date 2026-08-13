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
import com.agentsflex.asynctask.support.AsyncTaskTestSupport;

import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** 验证任务、上下文、查询参数与提交选项的防御性复制和不可变视图。 */
public class AsyncTaskValueObjectsTest {
    @Test
    public void shouldClassifyStatuses() {
        assertFalse(AsyncTaskStatus.SUBMITTING.isTerminal());
        assertFalse(AsyncTaskStatus.SUBMITTED.isTerminal());
        assertFalse(AsyncTaskStatus.RUNNING.isTerminal());
        assertTrue(AsyncTaskStatus.SUCCEEDED.isTerminal());
        assertTrue(AsyncTaskStatus.FAILED.isTerminal());
        assertTrue(AsyncTaskStatus.CANCELED.isTerminal());
        assertTrue(AsyncTaskStatus.TRACKING_TIMED_OUT.isTerminal());
        assertTrue(AsyncTaskStatus.SUBMIT_UNKNOWN.isTerminal());
        assertTrue(AsyncTaskStatus.SUBMITTED.isQueryable());
        assertTrue(AsyncTaskStatus.RUNNING.isQueryable());
        assertFalse(AsyncTaskStatus.SUCCEEDED.isQueryable());
    }

    @Test
    public void shouldDefensivelyCopyQueryParameters() {
        Map<String, Object> source = new HashMap<>();
        source.put("type", "batch");
        TaskQueryParams params = new TaskQueryParams("id-1");
        params.setProviderParams(source);
        source.put("type", "changed");
        assertEquals("batch", params.getProviderParams().get("type"));

        TaskQueryParams copy = params.copy();
        params.putProviderParam("region", "cn");
        assertFalse(copy.getProviderParams().containsKey("region"));
        try {
            params.getProviderParams().put("x", "y");
            fail("providerParams must be read-only");
        } catch (UnsupportedOperationException expected) { }
        TaskQueryParams empty = new TaskQueryParams();
        empty.setExternalTaskId("id-2");
        assertEquals("id-2", empty.getExternalTaskId());
        assertTrue(empty.getProviderParams().isEmpty());
    }

    @Test
    public void shouldExposeImmutableContextMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenant", "a");
        TaskSubmitContext submit = new TaskSubmitContext("task", 10, metadata);
        TaskQueryContext query = new TaskQueryContext("task", 2, 1, 1, 100, 10, metadata);
        metadata.put("tenant", "b");

        assertEquals("task", submit.getIdempotencyKey());
        assertEquals(10, submit.getCurrentTimeMillis());
        assertEquals("a", submit.getMetadata().get("tenant"));
        assertEquals("a", query.getMetadata().get("tenant"));
        assertEquals(2, query.getQueryCount());
        assertEquals(1, query.getConsecutiveErrors());
        assertEquals(1, query.getCreatedAt());
        assertEquals(100, query.getDeadlineAt());
        assertEquals(10, query.getCurrentTimeMillis());
        try {
            query.getMetadata().put("x", "y");
            fail("metadata must be read-only");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void shouldCopyTaskMutableContainers() {
        AsyncTask task = AsyncTaskTestSupport.task("copy", AsyncTaskStatus.RUNNING, 1, 100);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        task.setMetadata(metadata);
        task.getQueryParams().putProviderParam("stage", "one");

        AsyncTask copy = task.copy();
        task.getQueryParams().putProviderParam("stage", "two");
        task.setMetadata(new HashMap<String, Object>());

        assertEquals("one", copy.getQueryParams().getProviderParams().get("stage"));
        assertEquals("value", copy.getMetadata().get("key"));
    }
}
