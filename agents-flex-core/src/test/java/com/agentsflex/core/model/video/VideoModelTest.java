package com.agentsflex.core.model.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VideoModelTest {
    @Test
    public void shouldWaitForAsyncResult() {
        VideoModel model = new VideoModel() {
            @Override
            public VideoResponse generate(GenerateVideoRequest request) {
                VideoResponse response = new VideoResponse();
                response.setTaskId("task-1");
                response.setStatus(VideoTaskStatus.QUEUED);
                return response;
            }

            @Override
            public VideoResponse getResult(String taskId) {
                VideoResponse response = new VideoResponse();
                response.setTaskId(taskId);
                response.setStatus(VideoTaskStatus.SUCCEEDED);
                response.addVideo("https://example.com/result.mp4");
                return response;
            }
        };

        VideoResponse result = model.generateAndWait(new GenerateVideoRequest(), 1000, 1);
        assertEquals(VideoTaskStatus.SUCCEEDED, result.getStatus());
        assertEquals("https://example.com/result.mp4", result.getVideo().getUrl());
    }
}
