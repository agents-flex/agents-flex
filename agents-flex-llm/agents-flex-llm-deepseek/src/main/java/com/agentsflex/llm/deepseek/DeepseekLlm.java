/*
 * @(#) DeepseekLlm Created by tony on 2025-01-17 15:25
 * @copyright © 2018-2024 博信数科科技. All rights reserved.
 */
package com.agentsflex.llm.deepseek;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.BaseLlm;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.BaseLlmClientListener;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.client.LlmClient;
import com.agentsflex.core.llm.client.LlmClientListener;
import com.agentsflex.core.llm.client.impl.SseClient;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.llm.openai.OpenAiLLmUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author huangjf
 * @version : v1.0
 */
public class DeepseekLlm extends BaseLlm<DeepseekConfig> {

    private final Map<String, String> headers = new HashMap<>();
    private final HttpClient httpClient = new HttpClient();
    private final AiMessageParser aiMessageParser = OpenAiLLmUtil.getAiMessageParser(false);
    private final AiMessageParser streamMessageParser = OpenAiLLmUtil.getAiMessageParser(true);

    public DeepseekLlm(DeepseekConfig config) {
        super(config);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
    }

    public static DeepseekLlm of(String apiKey) {
        DeepseekConfig config = new DeepseekConfig();
        config.setApiKey(apiKey);
        return new DeepseekLlm(config);
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {

        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        String payload = DeepseekLLmUtil.promptToPayload(prompt, config, options, false);
        String endpoint = config.getEndpoint();
        String response = httpClient.post(endpoint + "/chat/completions", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

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
    public void chatStream(Prompt prompt, StreamResponseListener streamResponseListener, ChatOptions chatOptions) {
        LlmClient llmClient = new SseClient();
        String payload = DeepseekLLmUtil.promptToPayload(prompt, config, chatOptions, true);
        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, streamResponseListener, prompt, streamMessageParser);
        llmClient.start(endpoint + "/chat/completions", headers, payload, clientListener, config);
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener streamResponseListener) {
        chatStream(prompt, streamResponseListener, ChatOptions.DEFAULT);
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions embeddingOptions) {
        return null;
    }
}
