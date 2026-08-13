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
import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.store.*;


import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.VideoModel;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 验证视频模型提交、查询参数传递和成功/失败/处理中状态映射。 */
public class VideoAsyncTaskHandlerTest {
    @Test
    public void shouldSubmitAndQueryVideoTask() {
        AtomicReference<GenerateVideoRequest> submittedRequest = new AtomicReference<>();
        AtomicReference<String> queriedId = new AtomicReference<>();
        VideoResponse submitted = response(VideoTaskStatus.QUEUED, "video-1");
        VideoResponse completed = response(VideoTaskStatus.SUCCEEDED, "video-1");
        completed.addVideo("https://example.com/result.mp4");
        VideoAsyncTaskHandler handler = new VideoAsyncTaskHandler("video:test",
            model(submittedRequest, submitted, id -> { queriedId.set(id); return completed; }));
        assertEquals("video:test", handler.getKey());
        assertEquals(GenerateVideoRequest.class, handler.getSubmitParamsType());

        GenerateVideoRequest request = new GenerateVideoRequest();
        TaskSubmitResult submitResult = handler.submit(request, new TaskSubmitContext("task", 1, null));
        assertSame(request, submittedRequest.get());
        assertEquals(AsyncTaskStatus.RUNNING, submitResult.getStatus());
        assertEquals("video-1", submitResult.getQueryParams().getExternalTaskId());

        TaskQueryResult queryResult = handler.query(submitResult.getQueryParams(), queryContext());
        assertEquals("video-1", queriedId.get());
        assertEquals(AsyncTaskStatus.SUCCEEDED, queryResult.getStatus());
        assertSame(completed, queryResult.getResult());
        assertEquals("SUCCEEDED", queryResult.getProviderStatus());
    }

    @Test
    public void shouldMapAllVideoStatuses() {
        assertSubmitStatus(VideoTaskStatus.SUBMITTED, AsyncTaskStatus.SUBMITTED);
        assertSubmitStatus(VideoTaskStatus.QUEUED, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(VideoTaskStatus.RUNNING, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(VideoTaskStatus.UNKNOWN, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(VideoTaskStatus.TIMED_OUT, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(VideoTaskStatus.SUCCEEDED, AsyncTaskStatus.SUCCEEDED);
        assertSubmitStatus(VideoTaskStatus.FAILED, AsyncTaskStatus.FAILED);
        assertSubmitStatus(VideoTaskStatus.CANCELED, AsyncTaskStatus.CANCELED);
    }

    @Test
    public void shouldMapErrorsAndNullResponses() {
        VideoResponse failed = VideoResponse.error("E1", "failed");
        VideoAsyncTaskHandler handler = new VideoAsyncTaskHandler("video:test",
            model(new AtomicReference<>(), failed, id -> failed));
        TaskSubmitResult submitted = handler.submit(new GenerateVideoRequest(), new TaskSubmitContext("task", 1, null));
        assertEquals(AsyncTaskStatus.FAILED, submitted.getStatus());
        assertEquals("E1", submitted.getErrorCode());
        TaskQueryResult queried = handler.query(new TaskQueryParams("id"), queryContext());
        assertEquals(AsyncTaskStatus.FAILED, queried.getStatus());
        assertEquals("failed", queried.getErrorMessage());

        handler = new VideoAsyncTaskHandler("video:test",
            model(new AtomicReference<>(), null, id -> null));
        assertEquals(AsyncTaskStatus.FAILED,
            handler.submit(new GenerateVideoRequest(), new TaskSubmitContext("task", 1, null)).getStatus());
        assertEquals(AsyncTaskStatus.FAILED,
            handler.query(new TaskQueryParams("id"), queryContext()).getStatus());
    }

    @Test
    public void shouldValidateConstructor() {
        VideoModel model = model(new AtomicReference<>(), null, null);
        expect(() -> new VideoAsyncTaskHandler(null, model));
        expect(() -> new VideoAsyncTaskHandler(" ", model));
        expect(() -> new VideoAsyncTaskHandler("key", null));
    }

    private void assertSubmitStatus(VideoTaskStatus source, AsyncTaskStatus expected) {
        VideoResponse response = response(source, source.isTerminal() ? null : "id");
        TaskSubmitResult result = new VideoAsyncTaskHandler("video:test",
            model(new AtomicReference<>(), response, null))
            .submit(new GenerateVideoRequest(), new TaskSubmitContext("task", 1, null));
        assertEquals(expected, result.getStatus());
        if (source == VideoTaskStatus.SUCCEEDED) assertSame(response, result.getResult());
    }

    private VideoResponse response(VideoTaskStatus status, String taskId) {
        VideoResponse response = new VideoResponse();
        response.setStatus(status);
        response.setTaskId(taskId);
        return response;
    }

    private VideoModel model(AtomicReference<GenerateVideoRequest> request, VideoResponse submit, Query query) {
        return new VideoModel() {
            @Override public VideoResponse generate(GenerateVideoRequest value) { request.set(value); return submit; }
            @Override public VideoResponse getResult(String taskId) { return query == null ? null : query.get(taskId); }
        };
    }

    private TaskQueryContext queryContext() {
        return new TaskQueryContext("task", 0, 0, 1, 100, 2, null);
    }

    private void expect(Runnable runnable) {
        try { runnable.run(); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }

    private interface Query { VideoResponse get(String taskId); }
}
