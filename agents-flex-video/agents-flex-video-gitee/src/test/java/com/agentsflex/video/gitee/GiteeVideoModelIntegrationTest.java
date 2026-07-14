package com.agentsflex.video.gitee;

import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 在线集成测试。仅在显式设置 GITEE_AI_API_KEY 时执行，调用可能产生费用。 */
public class GiteeVideoModelIntegrationTest {

    @Test
    public void shouldGenerateTextVideo() {
        String apiKey = System.getenv("GITEE_AI_API_KEY");
        Assume.assumeTrue("GITEE_AI_API_KEY is required for the live test",
            apiKey != null && !apiKey.trim().isEmpty());

        GiteeVideoModelConfig config = new GiteeVideoModelConfig();
        config.setApiKey(apiKey);
        config.setModel(GiteeVideoModels.HAPPYHORSE_1_1);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setPrompt("一架红色纸飞机穿过清晨的未来城市，电影感运镜，飞行动作自然流畅");

        GiteeVideoModel model = new GiteeVideoModel(config);
        String existingTaskId = System.getenv("GITEE_AI_TASK_ID");
        VideoResponse result = existingTaskId == null || existingTaskId.trim().isEmpty()
            ? model.generateAndWait(request)
            : waitForExistingTask(model, existingTaskId, config.getTimeoutMillis(),
                config.getPollIntervalMillis());

        System.out.println("Gitee AI task id: " + result.getTaskId());
        System.out.println("Gitee AI task status: " + result.getStatus());
        System.out.println("Gitee AI error code: " + result.getErrorCode());
        System.out.println("Gitee AI error message: " + result.getErrorMessage());

        assertEquals("Video task failed: " + result, VideoTaskStatus.SUCCEEDED, result.getStatus());
        assertNotNull("Video result is missing", result.getVideo());
        assertTrue("Video URL is missing",
            result.getVideo().getUrl() != null && !result.getVideo().getUrl().isEmpty());

        File output = new File("target/gitee-generated.mp4");
        result.getVideo().writeToFile(output);
        assertTrue("Generated video was not downloaded", output.isFile() && output.length() > 0);

        System.out.println("Gitee AI video URL: " + result.getVideo().getUrl());
        System.out.println("Gitee AI video file: " + output.getAbsolutePath());
    }

    private VideoResponse waitForExistingTask(GiteeVideoModel model, String taskId,
                                                long timeoutMillis, long pollIntervalMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        VideoResponse response;
        do {
            response = model.getResult(taskId);
            if (response == null || response.isTerminal()) return response;
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Gitee AI task", e);
            }
        } while (System.currentTimeMillis() < deadline);
        return response;
    }
}
