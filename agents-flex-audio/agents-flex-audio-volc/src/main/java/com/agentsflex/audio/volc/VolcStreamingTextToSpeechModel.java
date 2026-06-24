package com.agentsflex.audio.volc;

import com.agentsflex.audio.volc.protocol.EventType;
import com.agentsflex.audio.volc.protocol.MsgType;
import com.agentsflex.audio.volc.protocol.VolcWebSocketClient;
import com.agentsflex.core.audio.tts.StreamingTextToSpeechListener;
import com.agentsflex.core.audio.tts.StreamingTextToSpeechModel;
import com.agentsflex.core.audio.tts.TextToSpeechOptions;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档：https://www.volcengine.com/docs/6561/2532486?lang=zh
 */
public class VolcStreamingTextToSpeechModel implements StreamingTextToSpeechModel {

    private static final Logger log = LoggerFactory.getLogger(VolcStreamingTextToSpeechModel.class);
    private final VolcTextToSpeechConfig config;
    private final List<StreamingTextToSpeechListener> listeners = new ArrayList<>();

    private TextToSpeechOptions options;
    private VolcWebSocketClient client;
    private String sessionId;
    private String uid;


    public VolcStreamingTextToSpeechModel(VolcTextToSpeechConfig config) {
        this.config = config;
    }

    @Override
    public void addListener(StreamingTextToSpeechListener listener) {
        listeners.add(listener);
    }

    @Override
    public void init(TextToSpeechOptions options) {
        this.options = options;
    }


    private void doInitClient() {
        // 设置请求头
        Map<String, Object> headers = Maps.of(
                "X-Api-Key", config.getApiKey())
            //seed-tts-2.0:豆包语音合成大模型2.0，支持使用豆包语音合成模型2.0音色
            //seed-icl-2.0:豆包声音复刻大模型2.0，支持使用声音复刻接口克隆的音色，具体音色详见控制台>音色库
            .set("X-Api-Resource-Id", config.getResourceId())
            .set("X-Api-Connect-Id", UUID.randomUUID().toString());

        String url = config.getWebSocketUrl();
        client = new VolcWebSocketClient(url, headers, listeners);
        client.connect();
        try {
            client.sendStartConnection();
            client.waitForMessage(MsgType.FULL_SERVER_RESPONSE, EventType.CONNECTION_STARTED);

            sessionId = UUID.randomUUID().toString();
            uid = UUID.randomUUID().toString();

            Map<String, Object> startReq = Maps.of(
                "user", Maps.of("uid", uid),
                "req_params", Maps.of(
                    "speaker", options.getVoiceOrDefault("zh_female_vv_uranus_bigtts"),
                    "audio_params", Maps.of(
                        "format", options.getFormatOrDefault("mp3"),
                        "sample_rate", options.getSampleRateOrDefault(16000),
                        "enable_timestamp", true),
                    "additions", JSON.toJSONString(Maps.of(
                        "disable_markdown_filter", true,
                        "disable_emoji_filter", true,
                        "max_length_to_filter_parenthesis", 100))),
                "event", EventType.START_SESSION.getValue());

            client.sendStartSession(JSON.toJSONBytes(startReq), sessionId);
            client.waitForMessage(MsgType.FULL_SERVER_RESPONSE, EventType.SESSION_STARTED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void sendText(String text) {
        if (StringUtil.noText(text)) {
            return;
        }

        if (client == null) {
            doInitClient();
        }

        Map<String, Object> currentReqParams = Maps.of(
            "format", options.getFormatOrDefault("mp3"),
            "sample_rate", options.getSampleRateOrDefault(16000),
            "enable_timestamp", true);

        currentReqParams.put("text", text);

        Map<String, Object> currentRequest = Maps.of(
            "user", Maps.of("uid", uid),
            "req_params", currentReqParams,
            "event", EventType.TASK_REQUEST.getValue());

        try {
            client.sendTaskRequest(JSON.toJSONBytes(currentRequest), sessionId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void close() throws IOException {
        try {
            client.sendFinishSession(sessionId);
            client.waitForMessage(MsgType.FULL_SERVER_RESPONSE, EventType.SESSION_FINISHED, true);
            client.sendFinishConnection();
            client.closeBlocking();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
