/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.store.milvus;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.alibaba.fastjson2.annotation.JSONField;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 向量存储配置类
 * <p>
 * 支持 YAML/JSON 配置序列化，敏感字段自动脱敏，符合企业级安全规范
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 方式 1：Builder 模式
 * MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
 *     .endpoint("http://localhost:19530")
 *     .token("root:Milvus")
 *     .defaultCollectionName("my_docs")
 *     .dimension(1536)
 *     .build();
 *
 * // 方式 2：YAML/JSON 反序列化
 * String json = "{\"endpoint\":\"http://localhost:19530\",\"defaultCollectionName\":\"docs\"}";
 * MilvusVectorStoreConfig config = JSON.parseObject(json, MilvusVectorStoreConfig.class);
 * }</pre>
 *
 * @author Agents-Flex Team
 * @since 2026-03-17
 */
public class MilvusVectorStoreConfig implements DocumentStoreConfig, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 配置类型标识
     */
    public static final String TYPE = "milvus";

    // ==================== 连接配置 ====================

    /**
     * Milvus 服务地址，格式：http://host:port 或 https://host:port
     * 示例：http://localhost:19530
     */
    private String endpoint;

    /**
     * 认证 Token（启用 RBAC 时必填）
     * 格式：用户名：密码 或 Zilliz Cloud API Key
     * 敏感字段：日志输出时自动脱敏
     */
    @JSONField(label = "sensitive")
    private String token;

    /**
     * 数据库名称（Milvus 2.4+ 支持多数据库，默认 "default"）
     */
    private String database = "default";

    // ==================== Collection 配置 ====================

    /**
     * 默认 Collection 名称
     */
    private String defaultCollectionName = "agents_flex_store";

    /**
     * 向量字段名称
     */
    private String vectorField = "vector";

    /**
     * 主键字段名称（支持 Int64 或 VarChar）
     */
    private String idField = "id";

    /**
     * 向量维度（必须与 Collection Schema 一致）
     */
    private Integer defaultDimension;

    /**
     * 相似度度量类型：COSINE / IP / L2
     */
    private String metricType = "COSINE";

    /**
     * 是否启用动态字段存储元数据
     */
    private boolean enableDynamicField = true;

    // ==================== 搜索配置 ====================

    /**
     * 默认搜索返回数量（topK）
     */
    private int defaultTopK = 10;

    /**
     * 搜索一致性级别：Strong / Session / Bounded / Eventually
     */
    private String consistencyLevel = "Session";

    /**
     * 内容字段名称（默认 "content"）
     */
    private String contentField = "content";

    /**
     * 标题字段名称（默认 "title"）
     */
    private String titleField = "title";

    // ==================== 扩展配置 ====================

    /**
     * 扩展属性（用于传递厂商特有参数或自定义配置）
     */
    private List<CreateCollectionReq.FieldSchema> extFields;

    // ==================== 构造函数 ====================

    public MilvusVectorStoreConfig() {
    }

    public MilvusVectorStoreConfig(String endpoint, String defaultCollectionName) {
        this.endpoint = endpoint;
        this.defaultCollectionName = defaultCollectionName;
    }

    // ==================== Getter / Setter ====================

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

    public String getVectorField() {
        return vectorField;
    }

    public void setVectorField(String vectorField) {
        this.vectorField = vectorField;
    }

    public String getIdField() {
        return idField;
    }

    public void setIdField(String idField) {
        this.idField = idField;
    }

    public Integer getDefaultDimension() {
        return defaultDimension;
    }

    public void setDefaultDimension(Integer defaultDimension) {
        this.defaultDimension = defaultDimension;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public boolean isEnableDynamicField() {
        return enableDynamicField;
    }

    public void setEnableDynamicField(boolean enableDynamicField) {
        this.enableDynamicField = enableDynamicField;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    public String getConsistencyLevel() {
        return consistencyLevel;
    }

    public void setConsistencyLevel(String consistencyLevel) {
        this.consistencyLevel = consistencyLevel;
    }

    public String getContentField() {
        return contentField;
    }

    public void setContentField(String contentField) {
        this.contentField = contentField;
    }

    public String getTitleField() {
        return titleField;
    }

    public void setTitleField(String titleField) {
        this.titleField = titleField;
    }

    public List<CreateCollectionReq.FieldSchema> getExtFields() {
        if (extFields == null) {
            extFields = new ArrayList<>();
        }
        return extFields;
    }

    public void setExtFields(List<CreateCollectionReq.FieldSchema> extFields) {
        this.extFields = extFields;
    }

    // ==================== 接口方法实现 ====================

    public String getType() {
        return TYPE;
    }

    public boolean checkAvailable() {
        if (StringUtils.isBlank(endpoint)) {
            throw new IllegalArgumentException("Milvus endpoint cannot be empty");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Endpoint must start with http:// or https://");
        }
        if (defaultDimension != null && defaultDimension <= 0) {
            throw new IllegalArgumentException("default dimension must be greater than 0");
        }
        if (!isValidMetricType(metricType)) {
            throw new IllegalArgumentException("Invalid metricType: " + metricType +
                ". Supported: COSINE, IP, L2");
        }

        return true;
    }

    private boolean isValidMetricType(String metricType) {
        return "COSINE".equalsIgnoreCase(metricType)
            || "IP".equalsIgnoreCase(metricType)
            || "L2".equalsIgnoreCase(metricType);
    }


    // ==================== 构建器模式 ====================

    public static Builder builder() {
        return new Builder();
    }


    public static class Builder {
        private final MilvusVectorStoreConfig config = new MilvusVectorStoreConfig();

        public Builder endpoint(String endpoint) {
            config.setEndpoint(endpoint);
            return this;
        }

        public Builder token(String token) {
            config.setToken(token);
            return this;
        }

        public Builder database(String database) {
            config.setDatabase(database);
            return this;
        }

        public Builder defaultCollectionName(String defaultCollectionName) {
            config.setDefaultCollectionName(defaultCollectionName);
            return this;
        }

        public Builder vectorField(String vectorField) {
            config.setVectorField(vectorField);
            return this;
        }

        public Builder idField(String idField) {
            config.setIdField(idField);
            return this;
        }

        public Builder defaultDimension(Integer dimension) {
            config.setDefaultDimension(dimension);
            return this;
        }

        public Builder metricType(String metricType) {
            config.setMetricType(metricType);
            return this;
        }

        public Builder enableDynamicField(boolean enableDynamicField) {
            config.setEnableDynamicField(enableDynamicField);
            return this;
        }

        public Builder defaultTopK(int defaultTopK) {
            config.setDefaultTopK(defaultTopK);
            return this;
        }

        public Builder consistencyLevel(String consistencyLevel) {
            config.setConsistencyLevel(consistencyLevel);
            return this;
        }

        public Builder contentField(String contentField) {
            config.setContentField(contentField);
            return this;
        }

        public Builder titleField(String titleField) {
            config.setTitleField(titleField);
            return this;
        }

        public Builder extField(CreateCollectionReq.FieldSchema fieldSchema) {
            config.getExtFields().add(fieldSchema);
            return this;
        }

        public MilvusVectorStoreConfig build() {
            config.checkAvailable();
            return config;
        }
    }

    // ==================== 脱敏 toString（防止敏感信息泄露） ====================

    @Override
    public String toString() {
        return "MilvusVectorStoreConfig{" +
            "endpoint='" + endpoint + '\'' +
            ", token='" + (token != null ? "***" : null) + '\'' +
            ", database='" + database + '\'' +
            ", defaultCollectionName='" + defaultCollectionName + '\'' +
            ", vectorField='" + vectorField + '\'' +
            ", idField='" + idField + '\'' +
            ", defaultDimension=" + defaultDimension +
            ", metricType='" + metricType + '\'' +
            ", enableDynamicField=" + enableDynamicField +
            ", defaultTopK=" + defaultTopK +
            ", consistencyLevel='" + consistencyLevel + '\'' +
            ", extProperties=" + extFields +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MilvusVectorStoreConfig that = (MilvusVectorStoreConfig) o;

        if (enableDynamicField != that.enableDynamicField) return false;
        if (defaultTopK != that.defaultTopK) return false;
        if (endpoint != null ? !endpoint.equals(that.endpoint) : that.endpoint != null) return false;
        if (token != null ? !token.equals(that.token) : that.token != null) return false;
        if (database != null ? !database.equals(that.database) : that.database != null) return false;
        if (defaultCollectionName != null ? !defaultCollectionName.equals(that.defaultCollectionName) : that.defaultCollectionName != null)
            return false;
        if (vectorField != null ? !vectorField.equals(that.vectorField) : that.vectorField != null) return false;
        if (idField != null ? !idField.equals(that.idField) : that.idField != null) return false;
        if (defaultDimension != null ? !defaultDimension.equals(that.defaultDimension) : that.defaultDimension != null)
            return false;
        if (metricType != null ? !metricType.equals(that.metricType) : that.metricType != null) return false;
        if (consistencyLevel != null ? !consistencyLevel.equals(that.consistencyLevel) : that.consistencyLevel != null)
            return false;
        return !(extFields != null ? !extFields.equals(that.extFields) : that.extFields != null);
    }

    @Override
    public int hashCode() {
        int result = endpoint != null ? endpoint.hashCode() : 0;
        result = 31 * result + (token != null ? token.hashCode() : 0);
        result = 31 * result + (database != null ? database.hashCode() : 0);
        result = 31 * result + (defaultCollectionName != null ? defaultCollectionName.hashCode() : 0);
        result = 31 * result + (vectorField != null ? vectorField.hashCode() : 0);
        result = 31 * result + (idField != null ? idField.hashCode() : 0);
        result = 31 * result + (defaultDimension != null ? defaultDimension.hashCode() : 0);
        result = 31 * result + (metricType != null ? metricType.hashCode() : 0);
        result = 31 * result + (enableDynamicField ? 1 : 0);
        result = 31 * result + defaultTopK;
        result = 31 * result + (consistencyLevel != null ? consistencyLevel.hashCode() : 0);
        result = 31 * result + (extFields != null ? extFields.hashCode() : 0);
        return result;
    }
}
