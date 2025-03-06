package com.agentsflex.image.volcengine;


import com.agentsflex.core.image.*;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.volcengine.service.visual.IVisualService;
import com.volcengine.service.visual.impl.VisualServiceImpl;



public class VolcengineImageModel implements ImageModel {

    private VolcengineImageModelConfig config;
    private IVisualService visualService = VisualServiceImpl.getInstance();

    public VolcengineImageModel(VolcengineImageModelConfig config) {
        this.config = config;
        visualService.setAccessKey(config.getAccessKey());
        visualService.setSecretKey(config.getSecretKey());
    }

    private ImageResponse processImageRequest(GenerateImageRequest request) {
        JSONObject req = new JSONObject(request.getOptions());
        ImageResponse responseimage = new ImageResponse();
        try {
            Object response = visualService.cvProcess(req);
            if (response instanceof JSONObject) {
                JSONObject jsonResponse = (JSONObject) response;
                JSONObject dataObject = jsonResponse.getJSONObject("data");
                JSONArray imageUrlsArray = dataObject.getJSONArray("image_urls");
                for (int i = 0; i < imageUrlsArray.size(); i++) {
                    responseimage.addImage(imageUrlsArray.getString(i));
                }
            } else {
                throw new RuntimeException("Unexpected response type: " + response.getClass().getName());
            }
            return responseimage;
        } catch (Exception e) {
            ImageResponse.error(e.getMessage());
            e.printStackTrace(); // 记录堆栈跟踪方便调试
            return responseimage;
        }
    }


    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        return processImageRequest(request);
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        return processImageRequest(request);
    }


    @Override
    public ImageResponse edit(EditImageRequest request) {
        throw new UnsupportedOperationException("not support edit image");
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        throw new UnsupportedOperationException("not support vary image");
    }

}
