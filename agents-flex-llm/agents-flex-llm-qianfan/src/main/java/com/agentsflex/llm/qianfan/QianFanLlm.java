package com.agentsflex.llm.qianfan;

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
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class QianFanLlm extends BaseLlm<QianFanLlmConfig> {
    private final HttpClient httpClient = new HttpClient();
    private final AiMessageParser aiMessageParser = QianFanLlmUtil.getAiMessageParser(false);
    private final AiMessageParser streamMessageParser = QianFanLlmUtil.getAiMessageParser(true);
    private final Map<String,String> headers = new HashMap<>();

    public QianFanLlm(QianFanLlmConfig config) {
        super(config);
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

    }

    public static QianFanLlm of(String apiKey) {
        QianFanLlmConfig config = new QianFanLlmConfig();
        config.setApiKey(apiKey);
        return new QianFanLlm(config);
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        String payload = QianFanLlmUtil.promptToPayload(prompt, config, options, false);
        String endpoint = config.getEndpoint();
        String response = httpClient.post(endpoint + "/chat/completions", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>the input:" + payload);
            System.out.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessageResponse messageResponse  = new AiMessageResponse(prompt, response, aiMessageParser.parse(jsonObject));

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        }

        return messageResponse;
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        String payload = QianFanLlmUtil.promptToPayload(prompt, config, options, true);
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, streamMessageParser);
        llmClient.start(config.getEndpoint() + "/chat/completions", headers, payload, clientListener, config);
    }



    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        String payload = QianFanLlmUtil.promptToEmbeddingsPayload(document, options, config);
        String response = httpClient.post(config.getEndpoint() + "/embeddings", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>the input:" + payload);
            System.out.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        double[] embedding = JSONPath.read(response, "$.data[0].embedding", double[].class);
        vectorData.setVector(embedding);

        return vectorData;
    }
}
