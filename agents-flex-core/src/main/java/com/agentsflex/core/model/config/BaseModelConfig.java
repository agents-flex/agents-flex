package com.agentsflex.core.model.config;

import java.io.Serializable;
import java.util.*;

public class BaseModelConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    protected String provider;
    protected String endpoint;
    protected String requestPath;
    protected String model;
    protected String apiKey;

    protected Map<String, Object> customProperties;

    // ---------- Getters and Setters ----------

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        if (endpoint != null && endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        this.endpoint = endpoint;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public void setRequestPath(String requestPath) {
        if (requestPath != null && !requestPath.startsWith("/")) {
            requestPath = "/" + requestPath;
        }
        this.requestPath = requestPath;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    // ---------- Custom Properties ----------

    public Map<String, Object> getCustomProperties() {
        return customProperties == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(customProperties);
    }

    public void setCustomProperties(Map<String, Object> customProperties) {
        this.customProperties = customProperties == null
            ? null
            : new HashMap<>(customProperties);
    }

    public void putCustomProperty(String key, Object value) {
        if (customProperties == null) {
            customProperties = new HashMap<>();
        }
        customProperties.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCustomProperty(String key, Class<T> type) {
        Object value = customProperties == null ? null : customProperties.get(key);
        if (value == null) return null;
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw new ClassCastException("Property '" + key + "' is not of type " + type.getSimpleName());
    }

    // ---------- Utility: Full URL ----------

    public String getFullUrl() {
        return (endpoint != null ? endpoint : "") +
            (requestPath != null ? requestPath : "");
    }

    // ---------- Object Methods ----------

    @Override
    public String toString() {
        return "BaseModelConfig{" +
            "provider='" + provider + '\'' +
            ", endpoint='" + endpoint + '\'' +
            ", requestPath='" + requestPath + '\'' +
            ", model='" + model + '\'' +
            ", apiKey='[REDACTED]'" +
            ", customProperties=" + (customProperties == null ? "null" : customProperties.toString()) +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseModelConfig that = (BaseModelConfig) o;
        return Objects.equals(provider, that.provider) &&
            Objects.equals(endpoint, that.endpoint) &&
            Objects.equals(requestPath, that.requestPath) &&
            Objects.equals(model, that.model) &&
            Objects.equals(apiKey, that.apiKey) &&
            Objects.equals(customProperties, that.customProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, endpoint, requestPath, model, apiKey, customProperties);
    }
}
