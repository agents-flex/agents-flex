//package com.agentsflex.image.volcengine;
//
//import com.agentsflex.core.model.image.GenerateImageRequest;
//import com.agentsflex.core.model.image.ImageResponse;
//import org.junit.Assume;
//import org.junit.Test;
//
//import java.io.File;
//
//import static org.junit.Assert.assertFalse;
//import static org.junit.Assert.assertTrue;
//
//public class VolcengineHomepageAssetsGenerationTest {
//    private static final String STYLE =
//        "Premium editorial product photography for a Java AI framework website, clean bright studio, " +
//        "white and graphite materials, electric blue and cyan light accents with a small coral-red accent, " +
//        "realistic materials, restrained technology aesthetic, crisp subject, balanced centered composition, " +
//        "wide 16:9 frame, readable at thumbnail size, no people, no letters, no words, no logos, no watermark, no border. ";
//
//    @Test
//    public void generateHomepageCapabilityImages() {
//        String apiKey = System.getenv("ARK_API_KEY");
//        Assume.assumeTrue(apiKey != null && !apiKey.trim().isEmpty());
//
//        VolcengineImageModelConfig config = new VolcengineImageModelConfig();
//        config.setApiKey(apiKey);
//        config.setModel(VolcengineImageModels.SEEDREAM_5_0_LITE);
//        VolcengineImageModel model = new VolcengineImageModel(config);
//
//        generate(model, "capability-image",
//            "A refined creative image-generation workstation: a slim pen tablet and stylus in the foreground, " +
//                "behind it several vivid photographic prints appear to emerge from a softly glowing cyan light plane, " +
//                "one print shows an abstract mountain landscape, another a coral geometric sculpture. ");
//        generate(model, "capability-tts",
//            "A text-to-speech transformation scene: a pristine white document sheet represented only by short embossed " +
//                "horizontal lines on the left, flowing luminous cyan audio waves crossing the center into a compact " +
//                "graphite studio speaker on the right, a small coral play control as a physical object. ");
//        generate(model, "capability-stt",
//            "A speech-to-text transformation scene: a premium graphite broadcast microphone on the left, a precise " +
//                "cyan waveform flowing across the center and resolving into neat horizontal transcript lines on a " +
//                "frosted glass panel on the right, with no readable characters. ");
//        generate(model, "capability-video",
//            "An AI video-generation studio scene: a compact professional cinema camera in the foreground aimed at three " +
//                "floating cinematic frames showing consecutive moments of a red paper airplane crossing a bright modern " +
//                "city, subtle frame progression and motion, elegant production lighting. ");
//    }
//
//    private void generate(VolcengineImageModel model, String name, String prompt) {
//        GenerateImageRequest request = new GenerateImageRequest();
//        request.setPrompt(STYLE + prompt);
//        request.setSizeString("2848x1600");
//        request.setResponseFormat("url");
//        request.setOutputFormat("png");
//        request.setWatermark(false);
//        request.setPromptExtend(true);
//
//        ImageResponse response = model.generate(request);
//        assertFalse("Generation failed for " + name + ": " + response, response.isError());
//        assertFalse("No image returned for " + name, response.getImages().isEmpty());
//
//        File output = new File("target/homepage-capabilities", name + ".png");
//        response.getImage().writeToFile(output);
//        assertTrue("Generated asset is empty: " + output, output.isFile() && output.length() > 0);
//        System.out.println("Homepage capability image: " + output.getAbsolutePath());
//    }
//}
