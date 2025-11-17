package com.agentsflex.embedding.openai;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.embedding.BaseEmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.JSONUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;

import java.util.HashMap;
import java.util.Map;

public class OpenAIEmbeddingModel extends BaseEmbeddingModel<OpenAIEmbeddingConfig> {

    private HttpClient httpClient = new HttpClient();

    public OpenAIEmbeddingModel(OpenAIEmbeddingConfig config) {
        super(config);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = promptToEmbeddingsPayload(document, options, config);
        String endpoint = config.getEndpoint();
        // https://platform.openai.com/docs/api-reference/embeddings/create
        String response = httpClient.post(endpoint + config.getRequestPath(), headers, payload);

        if (StringUtil.noText(response)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        double[] embedding = JSONUtil.readDoubleArray(JSON.parseObject(response), "$.data[0].embedding");
        vectorData.setVector(embedding);

        return vectorData;
    }


    public static String promptToEmbeddingsPayload(Document text, EmbeddingOptions options, OpenAIEmbeddingConfig config) {
        // https://platform.openai.com/docs/api-reference/making-requests
        return Maps.of("model", options.getModelOrDefault(config.getModel()))
            .set("encoding_format", "float")
            .set("input", text.getContent())
            .toJSON();
    }
}
