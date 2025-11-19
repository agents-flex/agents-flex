package com.agentsflex.llm.qianfan;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.log.ChatMessageLogger;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.BaseStreamClientListener;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.client.StreamClient;
import com.agentsflex.core.model.client.StreamClientListener;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class QianFanChatModel extends BaseChatModel<QianFanChatConfig> {
    private final HttpClient httpClient = new HttpClient();
    private final AiMessageParser aiMessageParser = QianFanLlmUtil.getAiMessageParser(false);
    private final AiMessageParser streamMessageParser = QianFanLlmUtil.getAiMessageParser(true);
    private final Map<String, String> headers = new HashMap<>();

    public QianFanChatModel(QianFanChatConfig config) {
        super(config);
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

    }

    public static QianFanChatModel of(String apiKey) {
        QianFanChatConfig config = new QianFanChatConfig();
        config.setApiKey(apiKey);
        return new QianFanChatModel(config);
    }

    @Override
    public AiMessageResponse doChat(Prompt prompt, ChatOptions options) {
        String payload = QianFanLlmUtil.promptToPayload(prompt, config, options, false);
        ChatMessageLogger.logRequest(config, payload);
        String response = httpClient.post(config.getFullUrl(), headers, payload);
        ChatMessageLogger.logResponse(config, response);
        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessageResponse messageResponse = new AiMessageResponse(prompt, response, aiMessageParser.parse(jsonObject));

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        }

        return messageResponse;
    }

    @Override
    public void doChatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        StreamClient streamClient = new SseClient();
        String payload = QianFanLlmUtil.promptToPayload(prompt, config, options, true);
        StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, listener, prompt, streamMessageParser);
        streamClient.start(config.getFullUrl(), headers, payload, clientListener, config);
    }

}
