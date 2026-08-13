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
package com.agentsflex.asynctask.support;
import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.store.*;


public final class AsyncTaskTestSupport {
    private AsyncTaskTestSupport() { }

    public static AsyncTask task(String id, AsyncTaskStatus status, long nextQueryAt, long deadlineAt) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setHandlerKey("test");
        task.setStatus(status);
        task.setQueryParams(new TaskQueryParams("external-" + id));
        task.setNextQueryAt(nextQueryAt);
        task.setDeadlineAt(deadlineAt);
        task.setCreatedAt(nextQueryAt);
        task.setUpdatedAt(nextQueryAt);
        return task;
    }

    public static AsyncTaskHandler<String> submittedHandler(String key) {
        return new AsyncTaskHandler<String>() {
            @Override public String getKey() { return key; }
            @Override public Class<String> getSubmitParamsType() { return String.class; }
            @Override public TaskSubmitResult submit(String params, TaskSubmitContext context) {
                TaskSubmitResult result = new TaskSubmitResult();
                result.setStatus(AsyncTaskStatus.SUBMITTED);
                result.setQueryParams(new TaskQueryParams("external"));
                return result;
            }
            @Override public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                TaskQueryResult result = new TaskQueryResult();
                result.setStatus(AsyncTaskStatus.RUNNING);
                return result;
            }
        };
    }
}
