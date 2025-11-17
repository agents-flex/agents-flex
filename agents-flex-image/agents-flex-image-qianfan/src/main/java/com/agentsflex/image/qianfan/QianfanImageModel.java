package com.agentsflex.image.qianfan;

import com.agentsflex.core.image.*;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class QianfanImageModel implements ImageModel {
    static final OkHttpClient HTTP_CLIENT = new OkHttpClient().newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();

    private QianfanImageModelConfig config;

    public QianfanImageModel(QianfanImageModelConfig config) {
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        ImageResponse responseImage = new ImageResponse();
        try {
            request.setModel(config.getModels());
            String payload = promptToPayload(request);

            RequestBody body = RequestBody.create(MediaType.parse("application/json"), payload);
            Request requestQianfan = new Request.Builder()
                .url(config.getEndpoint() + config.getEndpointGenerations())
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .build();

            Response response = HTTP_CLIENT.newCall(requestQianfan).execute();
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            JSONObject jsonObject = JSON.parseObject(response.body().string());
            JSONArray dataArray = jsonObject.getJSONArray("data");
            for (int i = 0; i < dataArray.size(); i++) {
                responseImage.addImage(dataArray.getJSONObject(i).getString("url"));
            }

            return responseImage;
        } catch (IOException e) {
            ImageResponse.error(e.getMessage());
            e.printStackTrace();
            return responseImage;
        } catch (Exception e) {
            ImageResponse.error(e.getMessage());
            e.printStackTrace();
            return responseImage;
        }
    }

    public static String promptToPayload(GenerateImageRequest request) {
        return Maps.of("Prompt", request.getPrompt())
            .setIfNotEmpty("model", request.getModel())
            .toJSON();
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        return null;
    }

    @Override
    public ImageResponse edit(EditImageRequest request) {
        return null;
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        return null;
    }
}
