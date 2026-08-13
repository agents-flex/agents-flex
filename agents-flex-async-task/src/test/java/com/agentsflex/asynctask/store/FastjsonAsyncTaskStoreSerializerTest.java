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
package com.agentsflex.asynctask.store;
import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import java.io.Serializable;

import static org.junit.Assert.*;

/** 验证默认 JSONB 序列化器的类型保真、安全白名单和错误边界。 */
public class FastjsonAsyncTaskStoreSerializerTest {
    /** 框架任务中的查询参数与 metadata 应能完整往返。 */
    @Test public void shouldRoundTripFrameworkTask() {
        FastjsonAsyncTaskStoreSerializer serializer = new FastjsonAsyncTaskStoreSerializer();
        AsyncTask task = new AsyncTask(); task.setId("task"); task.setStatus(AsyncTaskStatus.SUBMITTED);
        task.setQueryParams(new TaskQueryParams("external")); task.getQueryParams().putProviderParam("region", "cn");
        AsyncTask restored = serializer.deserialize(serializer.serialize(task), AsyncTask.class);
        assertEquals("task", restored.getId());
        assertEquals("external", restored.getQueryParams().getExternalTaskId());
        assertEquals("cn", restored.getQueryParams().getProviderParams().get("region"));
    }

    /** Object 类型字段中的业务 DTO 只有显式加入包名前缀后才能按原类型恢复。 */
    @Test public void shouldRestoreWhitelistedBusinessDto() {
        FastjsonAsyncTaskStoreSerializer serializer = new FastjsonAsyncTaskStoreSerializer("com.agentsflex.asynctask.");
        AsyncTask task = new AsyncTask(); task.setId("task"); task.setStatus(AsyncTaskStatus.SUCCEEDED); task.setResult(new TestResult("ok"));
        AsyncTask restored = serializer.deserialize(serializer.serialize(task), AsyncTask.class);
        assertTrue(restored.getResult() instanceof TestResult);
        assertEquals("ok", ((TestResult) restored.getResult()).value);
    }

    /** 无效白名单、空目标类型和不可序列化字段必须给出明确异常。 */
    @Test public void shouldRejectInvalidInputs() {
        expect(IllegalArgumentException.class, () -> new FastjsonAsyncTaskStoreSerializer(" "));
        FastjsonAsyncTaskStoreSerializer serializer = new FastjsonAsyncTaskStoreSerializer();
        assertNull(serializer.deserialize(null, AsyncTask.class));
        expect(IllegalArgumentException.class, () -> serializer.deserialize(new byte[]{1}, null));
        AsyncTask task = new AsyncTask(); task.setId("task"); task.setStatus(AsyncTaskStatus.SUCCEEDED); task.setResult(new Object());
        expect(IllegalArgumentException.class, () -> serializer.serialize(task));
    }

    private void expect(Class<? extends Throwable> type, Runnable call) { try { call.run(); fail("应抛出 " + type.getName()); } catch (Throwable error) { if (!type.isInstance(error)) throw error; } }
    private static final class TestResult implements Serializable { private static final long serialVersionUID=1L; private String value; private TestResult(){} private TestResult(String value){this.value=value;} }
}
