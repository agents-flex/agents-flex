package com.agentsflex.embedding.qwen;

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

public class QwenEmbeddingModel extends BaseEmbeddingModel<QwenEmbeddingConfig> {

    private HttpClient httpClient = new HttpClient();

    public QwenEmbeddingModel(QwenEmbeddingConfig config) {
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


    public static String promptToEmbeddingsPayload(Document text, EmbeddingOptions options, QwenEmbeddingConfig config) {
        //https://help.aliyun.com/zh/model-studio/developer-reference/embedding-interfaces-compatible-with-openai?spm=a2c4g.11186623.0.i3
        return Maps.of("model", options.getModelOrDefault(config.getModel()))
            .set("encoding_format", "float")
            .set("input", text.getContent())
            .toJSON();
    }
}
