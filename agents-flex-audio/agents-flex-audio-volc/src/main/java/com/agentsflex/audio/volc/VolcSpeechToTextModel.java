/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.audio.volc;

import com.agentsflex.core.audio.stt.SpeechToTextModel;
import com.agentsflex.core.audio.stt.SpeechToTextRequest;
import com.agentsflex.core.audio.stt.SpeechToTextResponse;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文档：https://www.volcengine.com/docs/6561/1631584?lang=zh
 */
public class VolcSpeechToTextModel implements SpeechToTextModel {
    private VolcSpeechToTextConfig config;
    private OkHttpClient okHttpClient;

    public VolcSpeechToTextModel(VolcSpeechToTextConfig config) {
        this.config = config;
        this.okHttpClient = OkHttpClientUtil.buildDefaultClient();
    }

    public VolcSpeechToTextConfig getConfig() {
        return config;
    }

    public void setConfig(VolcSpeechToTextConfig config) {
        this.config = config;
    }

    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    public void setOkHttpClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public SpeechToTextResponse stt(SpeechToTextRequest request) {

        String url = config.getUrl();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Api-Key", config.getApiKey());
        headers.put("X-Api-Resource-Id", "volc.bigasr.auc_turbo");
        headers.put("X-Api-Request-Id", UUID.randomUUID().toString());
        headers.put("X-Api-Sequence", "-1");


        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::addHeader);

        Map<String, String> audioInfo = new HashMap<>(1);
        if (StringUtil.hasText(request.getAudioUrl())) {
            audioInfo.put("url", request.getAudioUrl());
        } else {
            audioInfo.put("data", request.getAudioBase64());
        }

        Maps maps = Maps.of("user", Maps.of("uid", UUID.randomUUID().toString()))
            .set("audio", audioInfo)
            .set("request", Maps.of("model_name", "bigmodel"));

        RequestBody body = RequestBody.create(maps.toJSON(), MediaType.parse("application/json"));
        Request okHttpRequest = builder.method("POST", body).build();

        SpeechToTextResponse response = new SpeechToTextResponse();
        try (Response response1 = okHttpClient.newCall(okHttpRequest).execute()) {
            String status = response1.header("X-Api-Status-Code");
            String message = response1.header("X-Api-Message");
            if (status == null || !status.equals("20000000")) {
                response.setSuccess(false);
                response.setMessage(message);
            } else {
                ResponseBody responseBody = response1.body();
                if (responseBody == null) {
                    throw new IOException("No response body.");
                }
                String bodyString = responseBody.string();
                JSONObject obj = JSON.parseObject(bodyString);
                JSONArray sentences = obj.getJSONObject("result")
                    .getJSONArray("utterances");

                for (Object sentence : sentences) {
                    JSONObject json = (JSONObject) sentence;
                    String textString = json.getString("text");
                    response.addResult(textString);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return response;
    }


}
