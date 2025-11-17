package com.agentsflex.embedding.ollama;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.embedding.BaseEmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.JSONUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class OllamaEmbeddingModel extends BaseEmbeddingModel<OllamaEmbeddingConfig> {

    private HttpClient httpClient = new HttpClient();

    public OllamaEmbeddingModel(OllamaEmbeddingConfig config) {
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

        if (StringUtil.hasText(getConfig().getApiKey())) {
            headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        }

        String payload = Maps.of("model", options.getModelOrDefault(config.getModel()))
            .set("input", document.getContent())
            .toJSON();

        String endpoint = config.getEndpoint();
        // https://github.com/ollama/ollama/blob/main/docs/api.md#generate-embeddings
        String response = httpClient.post(endpoint + "/api/embed", headers, payload);

        if (StringUtil.noText(response)) {
            return null;
        }
        VectorData vectorData = new VectorData();

        JSONObject jsonObject = JSON.parseObject(response);
        double[] embedding = JSONUtil.readDoubleArray(jsonObject, "$.embeddings[0]");
        vectorData.setVector(embedding);

        vectorData.addMetadata("total_duration", JSONUtil.readLong(jsonObject, "$.total_duration"));
        vectorData.addMetadata("load_duration", JSONUtil.readLong(jsonObject, "$.load_duration"));
        vectorData.addMetadata("prompt_eval_count", JSONUtil.readInteger(jsonObject, "$.prompt_eval_count"));
        vectorData.addMetadata("model", JSONUtil.readString(jsonObject, "$.model"));

        return vectorData;
    }

}
