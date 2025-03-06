package com.agentsflex.image.volcengine;


import com.agentsflex.core.image.*;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.volcengine.service.visual.IVisualService;
import com.volcengine.service.visual.impl.VisualServiceImpl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class VolcengineImageModel implements ImageModel {

    private VolcengineImageModelConfig config;
    private HttpClient httpClient = new HttpClient();

    public VolcengineImageModel(VolcengineImageModelConfig config) {
        this.config = config;
    }


    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        IVisualService visualService = VisualServiceImpl.getInstance();

        visualService.setAccessKey(config.getAccessKey());
        visualService.setSecretKey(config.getSecretKey());

        JSONObject req = new JSONObject(request.getOptions());
        ImageResponse responseimage = new ImageResponse();
        Object response = null;
        try {
            response = visualService.cvProcess(req);

            if (response instanceof JSONObject) {
                JSONObject jsonResponse = (JSONObject) response;
                // 获取"data"对象
                JSONObject dataObject = jsonResponse.getJSONObject("data");
                // 获取"image_urls"数组
                JSONArray imageUrlsArray = dataObject.getJSONArray("image_urls");
                // 遍历并打印"image_urls"数组中的每个URL
                for (int i = 0; i < imageUrlsArray.size(); i++) {
                    responseimage.addImage(imageUrlsArray.getString(i));
                }

            }
            return responseimage;
        } catch(Exception e) {
           return responseimage.error(e.getMessage());
        }
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        IVisualService visualService = VisualServiceImpl.getInstance();
        // call below method if you dont set ak and sk in ～/.vcloud/config
        visualService.setAccessKey(config.getAccessKey());
        visualService.setSecretKey(config.getSecretKey());

        JSONObject req = new JSONObject(request.getOptions());
        ImageResponse responseimage = new ImageResponse();
        Object response = null;
        try {
            response = visualService.cvProcess(req);
            if (response instanceof JSONObject) {
                JSONObject jsonResponse = (JSONObject) response;
                // 获取"data"对象
                JSONObject dataObject = jsonResponse.getJSONObject("data");
                // 获取"image_urls"数组
                JSONArray imageUrlsArray = dataObject.getJSONArray("image_urls");
                // 遍历并打印"image_urls"数组中的每个URL
                for (int i = 0; i < imageUrlsArray.size(); i++) {
                    responseimage.addImage(imageUrlsArray.getString(i));
                }

            }
            return responseimage;
        } catch(Exception e) {
            return responseimage.error(e.getMessage());
        }
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
