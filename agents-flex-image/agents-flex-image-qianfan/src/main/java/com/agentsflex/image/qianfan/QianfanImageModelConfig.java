package com.agentsflex.image.qianfan;

import java.util.Map;
import java.util.function.Consumer;

public class QianfanImageModelConfig {

    private String endpoint = "https://qianfan.baidubce.com/v2";
    private  String endpointGenerations = "/images/generations";
    private  String models="irag-1.0";
    private String apiKey;
    private Consumer<Map<String, String>> headersConfig;

    public Consumer<Map<String, String>> getHeadersConfig() {
        return headersConfig;
    }

    public void setHeadersConfig(Consumer<Map<String, String>> headersConfig) {
        this.headersConfig = headersConfig;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getEndpointGenerations() {
        return endpointGenerations;
    }

    public void setEndpointGenerations(String endpointGenerations) {
        this.endpointGenerations = endpointGenerations;
    }

    public String getModels() {
        return models;
    }

    public void setModels(String models) {
        this.models = models;
    }

    @Override
    public String toString() {
        return "QianfanImageModelConfig{" +
            "endpoint='" + endpoint + '\'' +
            ", endpointGenerations='" + endpointGenerations + '\'' +
            ", models='" + models + '\'' +
            ", apiKey='" + apiKey + '\'' +
            ", headersConfig=" + headersConfig +
            '}';
    }
}
