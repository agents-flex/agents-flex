package com.agentsflex.audio.aliyun;

import com.agentsflex.core.audio.stt.SpeechToTextModel;
import com.agentsflex.core.audio.stt.SpeechToTextRequest;
import com.agentsflex.core.audio.stt.SpeechToTextResponse;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AliyunSpeechToTextModel implements SpeechToTextModel {
    private static final Logger log = LoggerFactory.getLogger(AliyunSpeechToTextModel.class);
    private AliyunSpeechToTextConfig config;
    private OkHttpClient okHttpClient;

    public AliyunSpeechToTextModel(AliyunSpeechToTextConfig config) {
        this.config = config;
        this.okHttpClient = OkHttpClientUtil.buildDefaultClient();
    }

    public AliyunSpeechToTextConfig getConfig() {
        return config;
    }

    public void setConfig(AliyunSpeechToTextConfig config) {
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

        String token = config.createToken();

        /**
         * 设置HTTPS REST POST请求
         * 1.使用http协议
         * 2.语音识别服务域名：nls-gateway-cn-shanghai.aliyuncs.com
         * 3.语音识别接口请求路径：/stream/v1/FlashRecognizer
         * 4.设置必须请求参数：appkey、token、format、sample_rate
         */
        String url = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/FlashRecognizer";
        url = url + "?appkey=" + config.getAppKey();
        url = url + "&token=" + token;

        String format = StringUtil.getFirstWithText(request.getOptions().getFormat(), "MP3");
        url = url + "&format=" + format;

        Integer sampleRate = request.getOptions().getSampleRate();
        if (sampleRate == null) {
            sampleRate = 16000;
        }
        url = url + "&sample_rate=" + sampleRate;

        /**
         * 设置HTTPS头部字段
         * 1.Content-Type：application/octet-stream
         */
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/octet-stream");


        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::addHeader);

        RequestBody body = RequestBody.create(request.getAudioBytes());
        Request okHttpRequest = builder.method("POST", body).build();

        StringBuilder text = new StringBuilder();
        try (Response response = okHttpClient.newCall(okHttpRequest).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("No response body.");
            }
            String bodyString = responseBody.string();
            JSONObject obj = JSON.parseObject(bodyString);
            Integer status = obj.getInteger("status");
            String message = obj.getString("message");
            if (status == null || !status.equals(20000000)) {
                log.error("语音识别失败：{}", obj);
                throw new RuntimeException(message);
            }
            JSONArray sentences = obj.getJSONObject("flash_result")
                .getJSONArray("sentences");

            for (Object sentence : sentences) {
                JSONObject json = (JSONObject) sentence;
                String textString = json.getString("text");
                text.append(textString);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return SpeechToTextResponse.of(text.toString());
    }
}
