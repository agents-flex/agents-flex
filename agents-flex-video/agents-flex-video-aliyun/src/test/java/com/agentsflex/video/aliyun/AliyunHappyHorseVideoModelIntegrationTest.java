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
// * HappyHorse 在线集成测试。仅在显式设置 DASHSCOPE_API_KEY 时执行，调用会产生费用。
// */
//public class AliyunHappyHorseVideoModelIntegrationTest {
//
//    @Test
//    public void shouldGenerateVideoWithHappyHorse11() {
//        String apiKey = System.getenv("DASHSCOPE_API_KEY");
//        Assume.assumeTrue("DASHSCOPE_API_KEY is required for the live test",
//            apiKey != null && !apiKey.trim().isEmpty());
//
//        AliyunHappyHorseVideoModelConfig config = new AliyunHappyHorseVideoModelConfig();
//        config.setApiKey(apiKey);
//        config.setModel(AliyunVideoModels.HAPPYHORSE_1_1_T2V);
//
//        GenerateVideoRequest request = new GenerateVideoRequest();
//        request.setPrompt("一架红色纸飞机穿过清晨的未来城市，电影感广角镜头，" +
//            "运镜平滑，光影真实，飞行动作自然流畅");
//        request.setResolution("720P");
//        request.setAspectRatio("16:9");
//        request.setDuration(5);
//        request.setWatermark(false);
//
//        AliyunHappyHorseVideoModel model = new AliyunHappyHorseVideoModel(config);
//        VideoResponse result = model.generateAndWait(request);
//
//        System.out.println("Aliyun HappyHorse task id: " + result.getTaskId());
//        System.out.println("Aliyun HappyHorse task status: " + result.getStatus());
//        System.out.println("Aliyun HappyHorse error code: " + result.getErrorCode());
//        System.out.println("Aliyun HappyHorse error message: " + result.getErrorMessage());
//
//        assertEquals("Video task failed: " + result, VideoTaskStatus.SUCCEEDED, result.getStatus());
//        assertNotNull("Video result is missing", result.getVideo());
//        assertTrue("Video URL is missing",
//            result.getVideo().getUrl() != null && !result.getVideo().getUrl().isEmpty());
//
//        File output = new File("target/aliyun-happyhorse-generated.mp4");
//        result.getVideo().writeToFile(output);
//        assertTrue("Generated video was not downloaded", output.isFile() && output.length() > 0);
//
//        System.out.println("Aliyun HappyHorse video URL: " + result.getVideo().getUrl());
//        System.out.println("Aliyun HappyHorse video file: " + output.getAbsolutePath());
//    }
//}
