package com.agentsflex.core.model.video;

import com.agentsflex.core.model.image.Image;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BaseVideoModelTest {
    @Test
    public void shouldValidateDeclaredEnumsBeforeCallingProvider() {
        BaseVideoConfig config = config();
        StubVideoModel model = new StubVideoModel(config);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setResolution("4K");

        assertRejected(model, request, "resolution");

        request.setResolution("1080p");
        request.setAspectRatio("1:1");
        assertRejected(model, request, "aspectRatio");

        request.setAspectRatio("16:9");
        request.setDuration(8);
        assertRejected(model, request, "duration");

        request.setDuration(5);
        request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
        assertRejected(model, request, "generationMode");
    }

    @Test
    public void shouldValidateModeInputsAndAudioCapability() {
        BaseVideoConfig config = config();
        config.setSupportedGenerationModes(Arrays.asList(
            VideoGenerationMode.TEXT_TO_VIDEO,
            VideoGenerationMode.FIRST_LAST_FRAME
        ));
        config.setSupportAudioGeneration(false);
        StubVideoModel model = new StubVideoModel(config);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setGenerationMode(VideoGenerationMode.FIRST_LAST_FRAME);

        assertRejected(model, request, "requires firstFrame and lastFrame");

        request.setGenerationMode(VideoGenerationMode.TEXT_TO_VIDEO);
        request.setGenerateAudio(true);
        assertRejected(model, request, "generateAudio");

        request.setGenerateAudio(false);
        request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
        assertRejected(model, request, "does not match request media");
    }

    @Test
    public void shouldSubmitSupportedAndUndeclaredModelRequests() {
        StubVideoModel model = new StubVideoModel(config());
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setResolution("1080p");
        request.setAspectRatio("16:9");
        request.setDuration(5);

        VideoResponse supported = model.generate(request);
        assertFalse(supported.isError());
        assertTrue(model.called);

        model.called = false;
        request.setModel("future-model");
        request.setResolution("future-resolution");
        VideoResponse undeclared = model.generate(request);
        assertFalse(undeclared.isError());
        assertTrue(model.called);
    }

    @Test
    public void shouldDistinguishVideoEditingFromOmniReference() {
        StubVideoModel model = new StubVideoModel(config());
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setSourceVideo(Video.ofUrl("https://example.com/source.mp4"));

        assertTrue(model.infer(request) == VideoGenerationMode.VIDEO_TO_VIDEO);

        request.addReferenceImage(Image.ofUrl("https://example.com/reference.png"));
        assertTrue(model.infer(request) == VideoGenerationMode.OMNI_REFERENCE);

        request.setSourceVideo(null);
        request.setAudioUrl("https://example.com/audio.mp3");
        assertTrue(model.infer(request) == VideoGenerationMode.OMNI_REFERENCE);

        request.setReferenceImages(null);
        request.setSourceVideo(Video.ofUrl("https://example.com/source.mp4"));
        assertTrue(model.infer(request) == VideoGenerationMode.AUDIO_DRIVEN);
    }

    @Test
    public void shouldAllowExplicitOmniReferenceWithSingleMediaType() {
        BaseVideoConfig config = config();
        config.setSupportedGenerationModes(Collections.singletonList(VideoGenerationMode.OMNI_REFERENCE));
        StubVideoModel model = new StubVideoModel(config);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setGenerationMode(VideoGenerationMode.OMNI_REFERENCE);
        request.setSourceVideo(Video.ofUrl("https://example.com/source.mp4"));

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        assertTrue(model.called);
    }

    private static BaseVideoConfig config() {
        BaseVideoConfig config = new BaseVideoConfig();
        config.setModel("known-model");
        config.setSupportedResolutions(Collections.singletonList("1080p"));
        config.setSupportedAspectRatios(Collections.singletonList("16:9"));
        config.setSupportedDurations(Collections.singletonList(5));
        config.setSupportedGenerationModes(Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO));
        return config;
    }

    private static void assertRejected(StubVideoModel model, GenerateVideoRequest request, String message) {
        VideoResponse response = model.generate(request);
        assertTrue(response.isError());
        assertTrue(response.getErrorMessage().contains(message));
        assertFalse(model.called);
    }

    private static class StubVideoModel extends BaseVideoModel<BaseVideoConfig> {
        private boolean called;

        private StubVideoModel(BaseVideoConfig config) { super(config); }

        @Override
        protected VideoResponse doGenerate(GenerateVideoRequest request) {
            called = true;
            VideoResponse response = new VideoResponse();
            response.setTaskId("task-1");
            response.setStatus(VideoTaskStatus.SUBMITTED);
            return response;
        }

        @Override
        public VideoResponse getResult(String taskId) { return new VideoResponse(); }

        private VideoGenerationMode infer(GenerateVideoRequest request) {
            return inferGenerationMode(request);
        }
    }
}
