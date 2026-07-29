package com.agentsflex.core.model.image;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BaseImageModelTest {
    @Test
    public void shouldValidateDeclaredCapabilitiesBeforeCallingProvider() {
        BaseImageConfig config = new BaseImageConfig();
        config.setModel("known-model");
        config.setSupportedResolutions(Collections.singletonList("1K"));
        config.setSupportedAspectRatios(Arrays.asList("1:1", "16:9"));
        config.setSupportedQualities(Arrays.asList("standard", "hd"));
        StubImageModel model = new StubImageModel(config);

        GenerateImageRequest request = new GenerateImageRequest();
        request.setResolution("2K");
        ImageResponse resolutionError = model.generate(request);
        assertTrue(resolutionError.isError());
        assertFalse(model.called);

        request.setResolution("1K");
        request.setQuality("ultra");
        ImageResponse qualityError = model.generate(request);
        assertTrue(qualityError.isError());
        assertFalse(model.called);

        request.setQuality("hd");
        request.addOption("aspectRatio", "4:3");
        ImageResponse ratioError = model.generate(request);
        assertTrue(ratioError.isError());
        assertFalse(model.called);
    }

    @Test
    public void shouldAllowDeclaredValuesAndUndeclaredModels() {
        BaseImageConfig config = new BaseImageConfig();
        config.setModel("known-model");
        config.setSupportedResolutions(Collections.singletonList("1K"));
        StubImageModel model = new StubImageModel(config);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel("custom-model");
        request.setResolution("custom-size");

        ImageResponse response = model.generate(request);

        assertFalse(response.isError());
        assertTrue(model.called);
    }

    private static class StubImageModel extends BaseImageModel<BaseImageConfig> {
        private boolean called;

        private StubImageModel(BaseImageConfig config) {
            super(config);
        }

        @Override
        protected ImageResponse doGenerate(GenerateImageRequest request) {
            called = true;
            return new ImageResponse();
        }

        @Override
        protected String resolveAspectRatioForValidation(GenerateImageRequest request) {
            Object value = request.getOption("aspectRatio");
            return value == null ? null : String.valueOf(value);
        }
    }
}
