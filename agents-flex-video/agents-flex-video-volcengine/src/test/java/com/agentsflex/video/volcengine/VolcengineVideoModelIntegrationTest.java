//package com.agentsflex.video.volcengine;
//
//import com.agentsflex.core.model.video.GenerateVideoRequest;
//import com.agentsflex.core.model.video.VideoResponse;
//import com.agentsflex.core.model.video.VideoTaskStatus;
//import org.junit.Assume;
//import org.junit.Test;
//
//import java.io.File;
//
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertNotNull;
//import static org.junit.Assert.assertTrue;
//
///**
// * Live integration test. Set ARK_API_KEY explicitly to enable it.
// */
//public class VolcengineVideoModelIntegrationTest {
//
//    @Test
//    public void shouldGenerateVideoWithSeedance() {
//        String apiKey = System.getenv("ARK_API_KEY");
//        Assume.assumeTrue("ARK_API_KEY is required for the live test", apiKey != null && !apiKey.trim().isEmpty());
//
//        VolcengineVideoModelConfig config = new VolcengineVideoModelConfig();
//        config.setApiKey(apiKey);
//        config.setModel(VolcengineVideoModels.SEEDANCE_2_0);
//
//        GenerateVideoRequest request = new GenerateVideoRequest();
//        request.setPrompt("A red paper airplane glides above a quiet futuristic city at sunrise, " +
//            "cinematic wide shot, smooth camera movement, realistic lighting");
//        request.setDuration(5);
//        request.setResolution("720p");
//        request.setAspectRatio("16:9");
//        request.setGenerateAudio(false);
//        request.setWatermark(false);
//
//        VolcengineVideoModel model = new VolcengineVideoModel(config);
//        VideoResponse result = model.generateAndWait(request, 10 * 60 * 1000L, 10 * 1000L);
//
//        System.out.println("Volcengine task id: " + result.getTaskId());
//        System.out.println("Volcengine task status: " + result.getStatus());
//        System.out.println("Volcengine error code: " + result.getErrorCode());
//        System.out.println("Volcengine error message: " + result.getErrorMessage());
//
//        assertEquals("Video task failed: " + result, VideoTaskStatus.SUCCEEDED, result.getStatus());
//        assertNotNull("Video result is missing", result.getVideo());
//        assertTrue("Video URL is missing", result.getVideo().getUrl() != null && !result.getVideo().getUrl().isEmpty());
//
//        File output = new File("target/volcengine-generated.mp4");
//        result.getVideo().writeToFile(output);
//        assertTrue("Generated video was not downloaded", output.isFile() && output.length() > 0);
//
//        System.out.println("Volcengine video URL: " + result.getVideo().getUrl());
//        System.out.println("Volcengine video file: " + output.getAbsolutePath());
//    }
//}
