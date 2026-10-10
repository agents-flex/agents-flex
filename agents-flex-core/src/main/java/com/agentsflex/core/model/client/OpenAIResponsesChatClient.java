package com.agentsflex.core.model.client;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.OpenAIResponsesParser;
import com.agentsflex.core.util.LocalTokenCounter;
import com.agentsflex.core.util.Retryer;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

/** HTTP client for the OpenAI Responses protocol. */
public class OpenAIResponsesChatClient extends ChatClient {
    private AgentsFlexHttpClient httpClient;
    private AiMessageParser<JSONObject> parser = new OpenAIResponsesParser();

    public OpenAIResponsesChatClient(BaseChatModel<?> chatModel) {
        super(chatModel);
    }

    public AgentsFlexHttpClient getHttpClient() {
        if (httpClient == null) httpClient = AgentsFlexHttpClient.getDefault();
        return httpClient;
    }

    public void setHttpClient(AgentsFlexHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public AiMessageParser<JSONObject> getAiMessageParser() {
        return parser;
    }

    public void setAiMessageParser(AiMessageParser<JSONObject> parser) {
        if (parser == null) throw new IllegalArgumentException("parser must not be null");
        this.parser = parser;
    }

    @Override
    public AiMessageResponse chat(String body) {
        ChatContext context = ChatContextHolder.currentContext();
        ChatRequestSpec spec = context.getRequestSpec();
        String response = spec.getRetryCount() > 0
            ? Retryer.retry(() -> getHttpClient().post(spec.getUrl(), spec.getHeaders(), body),
                spec.getRetryCount(), spec.getRetryInitialDelayMs())
            : getHttpClient().post(spec.getUrl(), spec.getHeaders(), body);
        if (StringUtil.noText(response)) return AiMessageResponse.error(context, response, "no content for response.");
        try {
            JSONObject root = JSON.parseObject(response);
            JSONObject error = root.getJSONObject("error");
            if (error != null && !error.isEmpty()) {
                AiMessageResponse result = AiMessageResponse.error(context, response, error.getString("message"));
                result.setErrorType(error.getString("type"));
                result.setErrorCode(error.getString("code"));
                return result;
            }
            AiMessage message = parser.parse(root, context);
            LocalTokenCounter.computeAndSetLocalTokens(context.getPrompt().getMessages(), message);
            return new AiMessageResponse(context, response, message);
        } catch (JSONException e) {
            return AiMessageResponse.error(context, response, "invalid json response.");
        }
    }

    @Override
    public void chatStream(String body, StreamResponseListener listener) {
        SseClient client = new SseClient();
        ChatContext context = ChatContextHolder.currentContext();
        BaseStreamClientListener streamListener = new BaseStreamClientListener(
            chatModel, context, client, listener, parser);
        ChatRequestSpec spec = context.getRequestSpec();
        if (spec.getRetryCount() > 0) {
            Retryer.retry(() -> {
                client.start(spec.getUrl(), spec.getHeaders(), body, streamListener, chatModel.getConfig());
                return null;
            }, spec.getRetryCount(), spec.getRetryInitialDelayMs());
        } else {
            client.start(spec.getUrl(), spec.getHeaders(), body, streamListener, chatModel.getConfig());
        }
    }
}
