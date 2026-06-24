package com.agentsflex.audio.volc;

import com.agentsflex.core.audio.tts.TextToSpeechModel;
import com.agentsflex.core.audio.tts.TextToSpeechRequest;
import com.agentsflex.core.audio.tts.TextToSpeechResponse;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文档：https://www.volcengine.com/docs/6561/2528925?lang=zh
 */
public class VolcTextToSpeechModel implements TextToSpeechModel {

    private VolcTextToSpeechConfig config;
    private OkHttpClient okHttpClient;

    public VolcTextToSpeechModel(VolcTextToSpeechConfig config) {
        this.config = config;
        this.okHttpClient = OkHttpClientUtil.buildDefaultClient();
    }

    public VolcTextToSpeechConfig getConfig() {
        return config;
    }

    public void setConfig(VolcTextToSpeechConfig config) {
        this.config = config;
    }

    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    public void setOkHttpClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public TextToSpeechResponse tts(TextToSpeechRequest request) {
        String apiKey = config.getApiKey();
        if (StringUtil.noText(apiKey)) {
            throw new IllegalArgumentException("apiKey is empty in VolcTextToSpeechConfig.");
        }

        String url = config.getHttpUrl();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Api-Key", apiKey);

        //请求的模型版本，可选值：
        //seed-tts-2.0:豆包语音合成大模型2.0，支持使用豆包语音合成模型2.0音色
        //seed-icl-2.0:豆包声音复刻大模型2.0，支持使用声音复刻接口克隆的音色，具体音色详见控制台>音色库
        headers.put("X-Api-Resource-Id", config.getResourceId());
        headers.put("X-Api-Request-Id", UUID.randomUUID().toString());


        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::addHeader);


        Maps maps = Maps.of("req_params", Maps.of("text", request.getText())
            .set("speaker", request.getOptions().getVoiceOrDefault("zh_female_vv_uranus_bigtts"))
            .set("audio_params", Maps.of("format", request.getOptions().getFormatOrDefault("mp3"))
                .set("sample_rate", request.getOptions().getSampleRateOrDefault(16000))
            )
        );

        RequestBody body = RequestBody.create(maps.toJSON(), MediaType.parse("application/json"));
        Request okHttpRequest = builder.method("POST", body).build();

        TextToSpeechResponse response = new TextToSpeechResponse();
        try (Response response1 = okHttpClient.newCall(okHttpRequest).execute()) {
            if (!response1.isSuccessful()) {
                throw new IOException("Unexpected code " + response1.code());
            }

            ResponseBody responseBody = response1.body();
            if (responseBody == null) {
                throw new IOException("No response body.");
            }

            // 逐行读取
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (StringUtil.noText(line)) {
                        continue;
                    }

                    JSONObject obj = JSON.parseObject(line);
                    int code = obj.getIntValue("code", 0);

                    String data = obj.getString("data");
                    if (data != null && !data.isEmpty()) {
                        byte[] chunkAudio = Base64.getDecoder().decode(data);
                        response.addResult(chunkAudio);
                    }

                    // 结束标志
                    if (code == 20000000) {
                        break;
                    }

                    // 错误处理
                    if (code > 0) {
                        String message = obj.getString("message");
                        response.setSuccess(false);
                        response.setMessage("Error code: " + code + " Message: " + message);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return response;
    }


}
