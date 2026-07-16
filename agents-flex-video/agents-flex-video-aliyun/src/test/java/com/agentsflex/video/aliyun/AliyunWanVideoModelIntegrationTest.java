//package com.agentsflex.video.aliyun;
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
// * Live integration test. Set DASHSCOPE_API_KEY explicitly to enable it.
// */
//public class AliyunWanVideoModelIntegrationTest {
//
//    @Test
//    public void shouldGenerateVideoWithWan26() {
//        String apiKey = System.getenv("DASHSCOPE_API_KEY");
//        Assume.assumeTrue("DASHSCOPE_API_KEY is required for the live test",
//            apiKey != null && !apiKey.trim().isEmpty());
//
//        AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
//        config.setApiKey(apiKey);
//        config.setModel(AliyunVideoModels.WAN_2_6_T2V);
//
//        GenerateVideoRequest request = new GenerateVideoRequest();
//        request.setPrompt("A red paper airplane flies through a futuristic city at sunrise, " +
//            "cinematic wide shot, smooth camera movement, realistic lighting");
//        request.setNegativePrompt("blurry, jittery, distorted, low quality");
//        request.setSize(1280, 720);
//        request.setDuration(5);
//        request.setPromptExtend(true);
//        request.setWatermark(false);
//
//        AliyunWanVideoModel model = new AliyunWanVideoModel(config);
//        VideoResponse result = model.generateAndWait(request);
//
//        System.out.println("Aliyun task id: " + result.getTaskId());
//        System.out.println("Aliyun task status: " + result.getStatus());
//        System.out.println("Aliyun error code: " + result.getErrorCode());
//        System.out.println("Aliyun error message: " + result.getErrorMessage());
//
//        assertEquals("Video task failed: " + result, VideoTaskStatus.SUCCEEDED, result.getStatus());
//        assertNotNull("Video result is missing", result.getVideo());
//        assertTrue("Video URL is missing",
//            result.getVideo().getUrl() != null && !result.getVideo().getUrl().isEmpty());
//
//        File output = new File("target/aliyun-wan-generated.mp4");
//        result.getVideo().writeToFile(output);
//        assertTrue("Generated video was not downloaded", output.isFile() && output.length() > 0);
//
//        System.out.println("Aliyun Wan video URL: " + result.getVideo().getUrl());
//        System.out.println("Aliyun Wan video file: " + output.getAbsolutePath());
//    }
//}
