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

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.util.MapUtil;
import com.agentsflex.core.util.StringUtil;
import com.google.gson.*;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储实现（Gson 版本）- 支持自动创建集合
 * <p>
 * 核心特性：
 * 1. 首次操作时自动创建 Collection，无需手动初始化
 * 2. 支持主键类型自动识别（String 对应 VarChar, Number 对应 Int64）
 * 3. 集合存在性缓存，避免重复检查，提升性能
 * 4. 向量索引自动创建（HNSW 算法 + 可配置度量类型）
 * 5. 资源自动管理：客户端连接由框架内部维护，用户无需手动关闭
 * 6. 修复 content/title 字段被误放入 $meta 的问题，支持双重解析策略
 * 7. 支持 contentField/titleField 自定义配置，适配不同业务场景
 * <p>
 * 使用说明：
 * 1. 通过 MilvusVectorStoreConfig 配置连接参数和集合参数
 * 2. 调用 create() 工厂方法或 Builder 模式创建实例
 * 3. 客户端连接由内部自动管理，无需手动调用 close()
 * 4. 相同配置的多个实例会自动复用客户端连接，减少资源开销
 *
 * @author Agents-Flex Team
 * @since 2026-03-17
 */
public class MilvusVectorStore extends DocumentStore {

    /**
     * 日志记录器
     */
    private static final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);

    /**
     * Gson 实例，用于 JSON 序列化/反序列化，线程安全
     */
    private static final Gson GSON = new Gson();

    /**
     * 默认向量字段名称
     */
    public static final String DEFAULT_VECTOR_FIELD = "vector";

    /**
     * 默认主键字段名称
     */
    public static final String DEFAULT_ID_FIELD = "id";

    /**
     * 默认内容字段名称
     */
    public static final String DEFAULT_CONTENT_FIELD = "content";

    /**
     * 默认标题字段名称
     */
    public static final String DEFAULT_TITLE_FIELD = "title";

    /**
     * 静态客户端连接池，key 为连接配置的唯一标识
     * 使用 ConcurrentHashMap 保证线程安全，相同配置的实例复用客户端
     */
    private static final ConcurrentHashMap<String, MilvusClientV2> clientPool = new ConcurrentHashMap<>();

    /**
     * 集合存在性缓存，key 为集合名称，value 为是否存在标记
     * 使用 ConcurrentHashMap 保证线程安全，避免重复创建集合
     */
    private final ConcurrentHashMap<String, Boolean> collectionCache = new ConcurrentHashMap<>();

    /**
     * Milvus 连接配置
     */
    private final ConnectConfig connectConfig;

    /**
     * 集合名称
     */
    private final String defaultCollectionName;

    /**
     * 向量字段名称
     */
    private final String vectorField;

    /**
     * 主键字段名称
     */
    private final String idField;

    /**
     * 内容字段名称，支持自定义配置
     */
    private final String contentField;

    /**
     * 标题字段名称，支持自定义配置
     */
    private final String titleField;

    /**
     * 向量维度，创建集合时必须指定
     */
    private Integer defaultDimension;

    /**
     * 相似度度量类型，默认 COSINE
     */
    private final IndexParam.MetricType metricType;

    /**
     * 是否启用动态字段存储元数据
     */
    private final boolean enableDynamicField;

    /**
     * 默认搜索返回数量（topK）
     */
    private final int defaultTopK;

    /**
     * 是否自动创建集合
     */
    private final boolean autoCreateCollection;

    /**
     * 保留字段集合，用于判断哪些字段不应放入 metadata
     * 使用 HashSet 提升查找性能，字段名统一转小写存储以支持大小写不敏感匹配
     */
    private final Set<String> reservedFields;

    /**
     * 连接配置的唯一标识，用于连接池复用
     */
    private final String poolKey;


    /**
     * 额外的扩展字段
     */
    private List<CreateCollectionReq.FieldSchema> extFields;

    /**
     * 主键类型
     */
    private DataType primaryKeyType = DataType.VarChar;


    /**
     * 静态初始化：注册 JVM 关闭钩子，确保应用退出时清理资源
     */
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Milvus client pool");
            for (MilvusClientV2 client : clientPool.values()) {
                try {
                    client.close();
                    logger.debug("Closed Milvus client for pool key");
                } catch (Exception e) {
                    logger.warn("Failed to close Milvus client during shutdown", e);
                }
            }
            clientPool.clear();
            logger.info("Milvus client pool shutdown complete");
        }));
    }

    /**
     * 工厂方法：根据配置创建 MilvusVectorStore 实例
     *
     * @param config 配置对象，不能为空
     * @return MilvusVectorStore 实例
     * @throws IllegalArgumentException 如果配置无效
     */
    public static MilvusVectorStore create(MilvusVectorStoreConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        config.checkAvailable();

        ConnectConfig.ConnectConfigBuilder connectBuilder = ConnectConfig.builder()
            .uri(config.getEndpoint())
            .dbName(config.getDatabase());

        if (StringUtil.hasText(config.getToken())) {
            connectBuilder.token(config.getToken());
        }

        return new Builder()
            .connectConfig(connectBuilder.build())
            .defaultCollectionName(config.getDefaultCollectionName())
            .vectorField(config.getVectorField())
            .idField(config.getIdField())
            .contentField(config.getContentField())
            .titleField(config.getTitleField())
            .defaultDimension(config.getDefaultDimension())
            .metricType(IndexParam.MetricType.valueOf(config.getMetricType()))
            .enableDynamicField(config.isEnableDynamicField())
            .defaultTopK(config.getDefaultTopK())
            .autoCreateCollection(true)
            .extFields(config.getExtFields())
            .primaryKeyType(config.getPrimaryKeyType())
            .build();
    }

    /**
     * 私有构造函数，通过 Builder 模式创建实例
     *
     * @param builder Builder 对象
     */
    private MilvusVectorStore(Builder builder) {
        this.connectConfig = Objects.requireNonNull(builder.connectConfig, "connectConfig cannot be null");
        this.defaultCollectionName = StringUtil.hasText(builder.defaultCollectionName)
            ? builder.defaultCollectionName : "agents_flex_store";
        this.vectorField = StringUtil.hasText(builder.vectorField)
            ? builder.vectorField : DEFAULT_VECTOR_FIELD;
        this.idField = StringUtil.hasText(builder.idField)
            ? builder.idField : DEFAULT_ID_FIELD;
        this.contentField = StringUtil.hasText(builder.contentField)
            ? builder.contentField : DEFAULT_CONTENT_FIELD;
        this.titleField = StringUtil.hasText(builder.titleField)
            ? builder.titleField : DEFAULT_TITLE_FIELD;
        this.defaultDimension = builder.defaultDimension;
        this.metricType = builder.metricType != null ? builder.metricType : IndexParam.MetricType.COSINE;
        this.enableDynamicField = builder.enableDynamicField;
        this.defaultTopK = builder.defaultTopK > 0 ? builder.defaultTopK : 10;
        this.autoCreateCollection = builder.autoCreateCollection;
        this.extFields = builder.extFields;
        this.primaryKeyType = builder.primaryKeyType != null ? builder.primaryKeyType : DataType.VarChar;

        // 预构建保留字段集合，统一转小写存储，支持大小写不敏感匹配
        this.reservedFields = new HashSet<>(Arrays.asList(
            vectorField.toLowerCase(),
            idField.toLowerCase(),
            contentField.toLowerCase(),
            titleField.toLowerCase(),
            "$meta"
        ));

        // 生成连接池 key，用于复用相同配置的客户端
        this.poolKey = generatePoolKey(connectConfig);
    }

    /**
     * 生成连接池的唯一标识
     * 基于连接配置的关键参数生成，相同配置的实例可复用客户端
     *
     * @param config 连接配置
     * @return 唯一标识字符串
     */
    private String generatePoolKey(ConnectConfig config) {
        // 使用 uri + dbName + token 的前 8 位作为 key，平衡唯一性和简洁性
        String uri = config.getUri() != null ? config.getUri() : "";
        String dbName = config.getDbName() != null ? config.getDbName() : "default";
        String token = config.getToken() != null ? config.getToken() : "";
        // token 可能较长，取前 8 位作为标识即可
        String tokenHash = token.length() > 8 ? token.substring(0, 8) : token;
        return uri + "|" + dbName + "|" + tokenHash;
    }

    /**
     * 获取 Milvus 客户端实例，从连接池获取或创建
     * 相同配置的多个 MilvusVectorStore 实例会复用同一个客户端
     *
     * @return MilvusClientV2 实例
     */
    private MilvusClientV2 getClient() {
        return MapUtil.computeIfAbsent(clientPool, poolKey, key -> {
            MilvusClientV2 client = new MilvusClientV2(connectConfig);
            logger.info("Created Milvus client for pool key: {}", poolKey);
            return client;
        });
    }

    /**
     * 确保集合存在，如果不存在则自动创建
     * 使用缓存避免重复检查，使用同步块保证线程安全
     */
    private void ensureCollectionExists(StoreOptions options) {
        if (!autoCreateCollection) {
            return;
        }

        String collectionName = options.getCollectionNameOrDefault(this.defaultCollectionName);

        // 先查缓存，避免重复请求
        if (Boolean.TRUE.equals(collectionCache.get(collectionName))) {
            return;
        }

        synchronized (this) {
            // 双重检查，避免并发创建
            if (Boolean.TRUE.equals(collectionCache.get(collectionName))) {
                return;
            }

            MilvusClientV2 milvusClient = getClient();
            try {
                // 检查集合是否存在
                DescribeCollectionReq describeReq = DescribeCollectionReq.builder()
                    .collectionName(collectionName)
                    .build();
                milvusClient.describeCollection(describeReq);

                // 集合已存在，加入缓存
                collectionCache.put(collectionName, true);
                logger.debug("Collection '{}' already exists", collectionName);

            } catch (Exception e) {
                String errorMsg = e.getMessage();
                // 判断是否为集合不存在的异常
                if (errorMsg != null && (errorMsg.contains("collection not found") || errorMsg.contains("can't find collection"))) {
                    // 集合不存在，创建新集合
                    createCollection(milvusClient, collectionName);
                    collectionCache.put(collectionName, true);
                    logger.info("Collection '{}' created successfully", collectionName);
                } else {
                    // 其他异常，抛出
                    throw new RuntimeException("Failed to check collection: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 创建 Collection 和向量索引
     * 显式添加 content/title 字段到 schema，确保查询时作为顶层字段返回
     *
     * @param milvusClient   Milvus 客户端实例
     * @param collectionName 集合名称
     */
    protected void createCollection(MilvusClientV2 milvusClient, String collectionName) {
        // 构建字段列表：主键 + 向量 + 可选的 content/title
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();

        // 添加主键字段
        fields.add(CreateCollectionReq.FieldSchema.builder()
            .name(idField)
            .dataType(this.primaryKeyType)
            .isPrimaryKey(true)
            .autoID(false)
            .build());


        EmbeddingModel embeddingModel = this.getEmbeddingModel();
        Integer dimension = embeddingModel != null ? embeddingModel.dimensions() : defaultDimension;

        // 添加向量字段
        fields.add(CreateCollectionReq.FieldSchema.builder()
            .name(vectorField)
            .dataType(DataType.FloatVector)
            .dimension(dimension)
            .build());

        // 如果配置了 content 字段，显式添加到 schema
        // 设置 nullable=true，允许插入时不提供该字段（会自动填充空值）
        if (StringUtil.hasText(contentField)) {
            fields.add(CreateCollectionReq.FieldSchema.builder()
                .name(contentField)
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .isNullable(true)
                .build());
        }

        // 如果配置了 title 字段且不与 content 重复，显式添加到 schema
        // 设置 nullable=true，允许插入时不提供该字段（会自动填充空值）
        if (StringUtil.hasText(titleField) && !titleField.equals(contentField)) {
            fields.add(CreateCollectionReq.FieldSchema.builder()
                .name(titleField)
                .dataType(DataType.VarChar)
                .maxLength(2048)
                .isNullable(true)
                .build());
        }


        // 添加扩展字段
        if (this.extFields != null && !this.extFields.isEmpty()) {
            fields.addAll(this.extFields);
        }


        // 构建 Schema
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
            .fieldSchemaList(fields)
            .enableDynamicField(enableDynamicField)
            .build();

        // 构建创建集合请求（enableDynamicField 只需在 schema 中设置一次）
        CreateCollectionReq createReq = CreateCollectionReq.builder()
            .collectionName(collectionName)
            .collectionSchema(schema)
            .build();

        milvusClient.createCollection(createReq);
        logger.debug("Created collection: {} with fields: {}", collectionName,
            fields.stream().map(CreateCollectionReq.FieldSchema::getName).collect(Collectors.toList()));

        // 创建向量索引，提升查询性能
        createVectorIndex(milvusClient, collectionName);
    }

//    /**
//     * 推断主键数据类型
//     * 当前默认返回 VarChar，可根据业务需求扩展支持 Int64
//     *
//     * @return DataType 枚举值
//     */
//    protected DataType inferPrimaryKeyType() {
//        // 默认使用 VarChar，兼容性更好，支持 UUID 等字符串主键
//        return DataType.VarChar;
//    }

    /**
     * 创建向量索引
     * 使用 HNSW 算法，适合高维向量的近似最近邻搜索
     *
     * @param milvusClient Milvus 客户端实例
     */
    protected void createVectorIndex(MilvusClientV2 milvusClient, String collectionName) {
        try {
            IndexParam indexParam = IndexParam.builder()
                .fieldName(vectorField)
                .indexName(vectorField + "_idx")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(metricType)
                .extraParams(new HashMap<String, Object>() {{
                    put("M", 16);
                    put("efConstruction", 64);
                }})
                .build();

            milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(Collections.singletonList(indexParam))
                .build());

            // 加载集合到内存，必需步骤，否则无法执行搜索
            milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());

            logger.debug("Vector index created and collection loaded for: {}", collectionName);
        } catch (Exception e) {
            // 索引创建失败不影响基本功能，记录警告日志
            logger.warn("Failed to create vector index (search may be slower): {}", e.getMessage());
        }
    }

    /**
     * 将 Document 对象转换为 JsonObject，用于存入 Milvus
     *
     * @param document 文档对象
     * @return JsonObject 表示的文档数据
     */
    private JsonObject documentToJson(Document document) {
        JsonObject json = new JsonObject();

        // 设置主键 ID
        Object documentId = document.getId();
        if (documentId != null) {
            if (this.primaryKeyType == DataType.VarChar || this.primaryKeyType == DataType.String) {
                json.addProperty(idField, documentId.toString());
            } else if (this.primaryKeyType == DataType.Int64) {
                if (documentId instanceof Number) {
                    json.addProperty(idField, ((Number) documentId).longValue());
                } else {
                    json.addProperty(idField, Long.parseLong(documentId.toString()));
                }
            } else if (this.primaryKeyType == DataType.Int32) {
                if (documentId instanceof Number) {
                    json.addProperty(idField, ((Number) documentId).intValue());
                } else {
                    json.addProperty(idField, Integer.parseInt(documentId.toString()));
                }
            } else if (this.primaryKeyType == DataType.Int16) {
                if (documentId instanceof Number) {
                    json.addProperty(idField, ((Number) documentId).shortValue());
                } else {
                    json.addProperty(idField, Short.parseShort(documentId.toString()));
                }
            } else {
                // 抛出异常，因为主键类型不符合预期
                throw new IllegalArgumentException("Unsupported primary key type for Milvus: " + this.primaryKeyType);
            }
        }

        // 设置向量数据
        if (document.getVector() != null) {
            JsonArray vectorArray = new JsonArray();
            for (float v : document.getVector()) {
                vectorArray.add(v);
            }
            json.add(vectorField, vectorArray);
        }

        // 使用配置的字段名存储 content/title，确保与 schema 定义一致
        // 即使为空也添加字段，避免 Milvus 报错 "field is not provided"
        if (StringUtil.hasText(document.getContent())) {
            json.addProperty(contentField, document.getContent());
        } else {
            json.addProperty(contentField, "");
        }

        if (StringUtil.hasText(document.getTitle())) {
            json.addProperty(titleField, document.getTitle());
        } else {
            json.addProperty(titleField, "");
        }

        if (this.extFields != null) {
            for (CreateCollectionReq.FieldSchema extField : this.extFields) {
                Object value = document.getMetadata(extField.getName());
                if (value instanceof Number) {
                    json.addProperty(extField.getName(), (Number) value);
                } else if (value instanceof String) {
                    json.addProperty(extField.getName(), (String) value);
                } else if (value instanceof Boolean) {
                    json.addProperty(extField.getName(), (Boolean) value);
                } else if (value != null) {
                    json.addProperty(extField.getName(), GSON.toJson(value));
                }
            }
        }


        // 动态字段存储其他元数据，排除已显式定义的字段和保留字段
        if (enableDynamicField && document.getMetadataMap() != null) {
            for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                String key = entry.getKey();
                // 排除保留字段和已显式定义的 content/title
                if (isReservedField(key) || isExtField(key) || key.equals(contentField) || key.equals(titleField)) {
                    continue;
                }
                Object value = entry.getValue();
                if (value instanceof String) {
                    json.addProperty(key, (String) value);
                } else if (value instanceof Number) {
                    json.addProperty(key, (Number) value);
                } else if (value instanceof Boolean) {
                    json.addProperty(key, (Boolean) value);
                } else if (value != null) {
                    // 复杂对象转为 JSON 字符串存储
                    json.addProperty(key, GSON.toJson(value));
                }
            }
        }
        return json;
    }

    private boolean isExtField(String fieldName) {
        if (extFields == null || extFields.isEmpty()) {
            return false;
        }

        for (CreateCollectionReq.FieldSchema extField : extFields) {
            if (extField.getName().equals(fieldName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断字段是否为保留字段
     * 使用小写比较，支持大小写不敏感匹配
     *
     * @param key 字段名
     * @return 是否为保留字段
     */
    private boolean isReservedField(String key) {
        if (key == null) {
            return true;
        }
        return reservedFields.contains(key.toLowerCase());
    }

    /**
     * 从 Milvus 搜索结果中提取字段值
     * 支持大小写不敏感匹配，兼容不同返回格式
     * 空字符串视为无效值，返回 null
     *
     * @param entity    实体数据 Map
     * @param fieldName 目标字段名
     * @return 字段值字符串，如果不存在返回 null
     */
    private String extractFieldValue(Map<String, Object> entity, String fieldName) {
        if (entity == null || fieldName == null) {
            return null;
        }
        // 优先精确匹配
        if (entity.containsKey(fieldName)) {
            Object value = entity.get(fieldName);
            if (value == null) {
                return null;
            }
            String strValue = String.valueOf(value);
            // 空字符串视为无效值，返回 null
            return StringUtil.noText(strValue) ? null : strValue;
        }
        // 降级：大小写不敏感匹配，兼容 Milvus 可能返回不同大小写的字段名
        for (Map.Entry<String, Object> entry : entity.entrySet()) {
            if (fieldName.equalsIgnoreCase(entry.getKey())) {
                Object value = entry.getValue();
                if (value == null) {
                    return null;
                }
                String strValue = String.valueOf(value);
                return StringUtil.noText(strValue) ? null : strValue;
            }
        }
        return null;
    }

    /**
     * 从 $meta 字段解析动态字段
     * 兼容 pure dynamic field 场景，当 content/title 未显式定义在 schema 中时，会从 $meta 中解析
     *
     * @param entity 实体数据 Map
     * @return 解析后的动态字段 Map
     */
    private Map<String, Object> extractMetaFields(Map<String, Object> entity) {
        Map<String, Object> result = new HashMap<>();
        if (entity == null) {
            return result;
        }

        Object metaObj = entity.get("$meta");
        if (metaObj == null) {
            return result;
        }

        // 情况 1: $meta 已经是 Map（Milvus SDK 可能直接解析）
        if (metaObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = (Map<String, Object>) metaObj;
            result.putAll(metaMap);
        }
        // 情况 2: $meta 是 JSON 字符串
        else if (metaObj instanceof String) {
            try {
                JsonObject json = GSON.fromJson((String) metaObj, JsonObject.class);
                if (json != null) {
                    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                        result.put(entry.getKey(), parseJsonValue(entry.getValue()));
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse $meta JSON: {}", metaObj, e);
            }
        }
        // 情况 3: $meta 是 JsonObject 类型
        else if (metaObj instanceof JsonObject) {
            JsonObject json = (JsonObject) metaObj;
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                result.put(entry.getKey(), parseJsonValue(entry.getValue()));
            }
        }

        return result;
    }

    /**
     * 解析 JsonElement 为 Java 对象
     *
     * @param element JsonElement 对象
     * @return 解析后的 Java 对象
     */
    private Object parseJsonValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive prim = element.getAsJsonPrimitive();
            if (prim.isBoolean()) {
                return prim.getAsBoolean();
            }
            if (prim.isNumber()) {
                return prim.getAsNumber();
            }
            return prim.getAsString();
        } else if (element.isJsonArray()) {
            // 简单处理：数组转为 List<String>，复杂场景可按需扩展
            List<String> list = new ArrayList<>();
            for (JsonElement e : element.getAsJsonArray()) {
                list.add(e.getAsString());
            }
            return list;
        } else if (element.isJsonObject()) {
            // 嵌套对象转为 JSON 字符串存储
            return GSON.toJson(element.getAsJsonObject());
        }
        return element.toString();
    }

    /**
     * 将 Milvus 搜索结果转换为 Document 对象
     * 采用双重解析策略：优先读取顶层字段，不存在则从 $meta 中解析
     *
     * @param result 搜索结果项
     * @return Document 对象
     */
    private Document searchResultToDocument(SearchResp.SearchResult result) {
        Document document = new Document();
        document.setId(result.getId());
        document.setScore(result.getScore() != null ? result.getScore() : null);

        Map<String, Object> entity = result.getEntity();
        if (entity == null || entity.isEmpty()) {
            return document;
        }

        // 策略 1：优先从顶层字段读取 content/title
        String content = extractFieldValue(entity, contentField);
        String title = extractFieldValue(entity, titleField);

        // 策略 2：如果顶层没有，尝试从 $meta 中解析（兼容 pure dynamic field 场景）
        if (StringUtil.noText(content) || StringUtil.noText(title)) {
            Map<String, Object> metaFields = extractMetaFields(entity);
            if (!metaFields.isEmpty()) {
                if (StringUtil.noText(content)) {
                    Object metaContent = metaFields.get(contentField);
                    if (metaContent != null) {
                        content = String.valueOf(metaContent);
                    }
                }
                if (StringUtil.noText(title)) {
                    Object metaTitle = metaFields.get(titleField);
                    if (metaTitle != null) {
                        title = String.valueOf(metaTitle);
                    }
                }
                // 将 $meta 中的其他字段加入 metadata（排除已提取的 content/title）
                for (Map.Entry<String, Object> entry : metaFields.entrySet()) {
                    String key = entry.getKey();
                    if (!isReservedField(key) && !key.equals(contentField) && !key.equals(titleField)) {
                        document.putMetadata(key, entry.getValue());
                    }
                }
            }
        }

        // 设置 content 和 title（空字符串不设置，保持为 null）
        if (StringUtil.hasText(content)) {
            document.setContent(content);
        }

        if (StringUtil.hasText(title)) {
            document.setTitle(title);
        }

        // 提取向量数据

        if (entity.containsKey(vectorField)) {
            Object vectorListObject = entity.get(vectorField);
            if (vectorListObject instanceof List) {
                List<?> vecList = (List<?>) vectorListObject;
                float[] vector = new float[vecList.size()];
                for (int i = 0; i < vecList.size(); i++) {
                    Object val = vecList.get(i);
                    if (val instanceof Number) {
                        vector[i] = ((Number) val).floatValue();
                    }
                }
                document.setVector(vector);
            }
        }

        // 添加其他顶层非保留字段到 metadata（排除 $meta 本身）
        for (Map.Entry<String, Object> entry : entity.entrySet()) {
            String fieldName = entry.getKey();
            // 跳过：保留字段、$meta（已解析）、已设置的 content/title
            if (fieldName == null || isReservedField(fieldName) || "$meta".equals(fieldName)
                || fieldName.equalsIgnoreCase(contentField) || fieldName.equalsIgnoreCase(titleField) || fieldName.equalsIgnoreCase(idField)) {
                continue;
            }
            document.putMetadata(fieldName, entry.getValue());
        }

        return document;
    }

    /**
     * 存储文档到 Milvus
     *
     * @param documents 文档列表
     * @param options   存储选项
     * @return StoreResult 操作结果
     */
    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }

        try {
            // 确保集合存在
            ensureCollectionExists(options);

            MilvusClientV2 milvusClient = getClient();

            // 转换文档为 JSON 列表
            List<JsonObject> dataList = documents.stream()
                .map(this::documentToJson)
                .collect(Collectors.toList());

            String collectionName = options.getCollectionNameOrDefault(this.defaultCollectionName);

            // 执行插入操作
            milvusClient.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(dataList)
                .build());

            return StoreResult.success();
        } catch (Exception e) {
            logger.error("Failed to store documents to Milvus", e);
            return StoreResult.fail("Store failed: " + e.getMessage(), e);
        }
    }

    /**
     * 搜索相似文档
     *
     * @param wrapper 搜索条件封装对象
     * @param options 搜索选项
     * @return 匹配的文档列表
     */
    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        if (wrapper == null || wrapper.getVector() == null) {
            return Collections.emptyList();
        }

        try {
            // 确保集合存在
            ensureCollectionExists(options);

            MilvusClientV2 milvusClient = getClient();

            String collectionName = options.getCollectionNameOrDefault(this.defaultCollectionName);

            // 构建搜索请求
            SearchReq.SearchReqBuilder searchReqBuilder = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(new FloatVec(wrapper.getVector())))
                .limit(wrapper.getMaxResults() != null ? wrapper.getMaxResults() : defaultTopK)
                .outputFields(wrapper.getOutputFields() != null ? wrapper.getOutputFields() : Collections.singletonList("*"));

            // 添加过滤条件
            String filter = wrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT);
            if (StringUtil.hasText(filter)) {
                searchReqBuilder.filter(filter);
            }

            // 添加分区名称
            List<String> partitionNames = options.getPartitionNames();
            if (partitionNames != null && !partitionNames.isEmpty()) {
                searchReqBuilder.partitionNames(partitionNames);
            }

            // 执行搜索
            SearchReq searchReq = searchReqBuilder.build();
            SearchResp response = milvusClient.search(searchReq);
            List<List<SearchResp.SearchResult>> results = response.getSearchResults();

            if (results == null || results.isEmpty() || results.get(0) == null) {
                return Collections.emptyList();
            }

            // 转换搜索结果
            return results.get(0).stream()
                .map(this::searchResultToDocument)
                .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to search documents from Milvus", e);
            return Collections.emptyList();
        }
    }

    /**
     * 更新文档
     *
     * @param documents 文档列表
     * @param options   更新选项
     * @return StoreResult 操作结果
     */
    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }

        try {
            // 确保集合存在
            ensureCollectionExists(options);

            MilvusClientV2 milvusClient = getClient();

            // 转换文档为 JSON 列表
            List<JsonObject> dataList = documents.stream()
                .map(this::documentToJson)
                .collect(Collectors.toList());

            String collectionName = options.getCollectionNameOrDefault(this.defaultCollectionName);

            // 执行 upsert 操作
            milvusClient.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(dataList)
                .build());

            return StoreResult.success();
        } catch (Exception e) {
            logger.error("Failed to update documents in Milvus", e);
            return StoreResult.fail("Update failed: " + e.getMessage(), e);
        }
    }

    /**
     * 删除文档
     *
     * @param ids     主键 ID 集合
     * @param options 删除选项
     * @return StoreResult 操作结果
     */
    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }

        try {
            // 确保集合存在
            ensureCollectionExists(options);

            MilvusClientV2 milvusClient = getClient();

            // 构建过滤表达式
            List<Object> idList = new ArrayList<>(ids);
            String filter = buildFilterExpression(idList);

            String collectionName = options.getCollectionNameOrDefault(this.defaultCollectionName);

            // 执行删除操作
            milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter(filter)
                .build());

            return StoreResult.success();
        } catch (Exception e) {
            logger.error("Failed to delete documents from Milvus", e);
            return StoreResult.fail("Delete failed: " + e.getMessage(), e);
        }
    }

    /**
     * 构建删除操作的过滤表达式
     *
     * @param idList ID 列表
     * @return 过滤表达式字符串
     */
    private String buildFilterExpression(List<Object> idList) {
        if (idList.size() == 1) {
            Object id = idList.get(0);
            return id instanceof String
                ? String.format("%s == '%s'", idField, id)
                : String.format("%s == %s", idField, id);
        }

        StringBuilder inClause = new StringBuilder().append(idField).append(" in [");
        for (int i = 0; i < idList.size(); i++) {
            Object id = idList.get(i);
            inClause.append(id instanceof String ? "'" + id + "'" : id);
            if (i < idList.size() - 1) {
                inClause.append(", ");
            }
        }
        return inClause.append("]").toString();
    }

    /**
     * 获取集合名称
     *
     * @return 集合名称
     */
    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    /**
     * 获取向量字段名称
     *
     * @return 向量字段名称
     */
    public String getVectorField() {
        return vectorField;
    }

    /**
     * 获取内容字段名称
     *
     * @return 内容字段名称
     */
    public String getContentField() {
        return contentField;
    }

    /**
     * 获取标题字段名称
     *
     * @return 标题字段名称
     */
    public String getTitleField() {
        return titleField;
    }

    /**
     * 获取向量维度
     *
     * @return 向量维度
     */
    public Integer getDefaultDimension() {
        return defaultDimension;
    }

    /**
     * Builder 模式构建器
     */
    public static class Builder {
        private ConnectConfig connectConfig;
        private String defaultCollectionName;
        private String vectorField = DEFAULT_VECTOR_FIELD;
        private String idField = DEFAULT_ID_FIELD;
        private String contentField = DEFAULT_CONTENT_FIELD;
        private String titleField = DEFAULT_TITLE_FIELD;
        private Integer defaultDimension;
        private IndexParam.MetricType metricType = IndexParam.MetricType.COSINE;
        private boolean enableDynamicField = true;
        private int defaultTopK = 10;
        private boolean autoCreateCollection = true;
        private List<CreateCollectionReq.FieldSchema> extFields;
        private DataType primaryKeyType = DataType.VarChar;

        /**
         * 设置连接配置
         *
         * @param config ConnectConfig 对象
         * @return Builder 实例
         */
        public Builder connectConfig(ConnectConfig config) {
            this.connectConfig = config;
            return this;
        }

        /**
         * 设置 Milvus 服务地址
         *
         * @param uri 服务地址，格式：http://host:port
         * @return Builder 实例
         */
        public Builder uri(String uri) {
            this.connectConfig = ConnectConfig.builder()
                .uri(uri)
                .token(this.connectConfig != null ? this.connectConfig.getToken() : null)
                .dbName(this.connectConfig != null ? this.connectConfig.getDbName() : null)
                .build();
            return this;
        }

        /**
         * 设置认证 Token
         *
         * @param token 认证 Token
         * @return Builder 实例
         */
        public Builder token(String token) {
            this.connectConfig = ConnectConfig.builder()
                .uri(this.connectConfig != null ? this.connectConfig.getUri() : null)
                .token(token)
                .dbName(this.connectConfig != null ? this.connectConfig.getDbName() : null)
                .build();
            return this;
        }

        /**
         * 设置集合名称
         *
         * @param name 集合名称
         * @return Builder 实例
         */
        public Builder defaultCollectionName(String name) {
            this.defaultCollectionName = name;
            return this;
        }

        /**
         * 设置向量字段名称
         *
         * @param field 字段名称
         * @return Builder 实例
         */
        public Builder vectorField(String field) {
            this.vectorField = field;
            return this;
        }

        /**
         * 设置主键字段名称
         *
         * @param field 字段名称
         * @return Builder 实例
         */
        public Builder idField(String field) {
            this.idField = field;
            return this;
        }

        /**
         * 设置内容字段名称
         *
         * @param field 字段名称
         * @return Builder 实例
         */
        public Builder contentField(String field) {
            this.contentField = field;
            return this;
        }

        /**
         * 设置标题字段名称
         *
         * @param field 字段名称
         * @return Builder 实例
         */
        public Builder titleField(String field) {
            this.titleField = field;
            return this;
        }

        /**
         * 设置向量维度
         *
         * @param dim 向量维度
         * @return Builder 实例
         */
        public Builder defaultDimension(Integer dim) {
            this.defaultDimension = dim;
            return this;
        }

        /**
         * 设置相似度度量类型
         *
         * @param type MetricType 枚举值
         * @return Builder 实例
         */
        public Builder metricType(IndexParam.MetricType type) {
            this.metricType = type;
            return this;
        }

        /**
         * 设置是否启用动态字段
         *
         * @param enable 是否启用
         * @return Builder 实例
         */
        public Builder enableDynamicField(boolean enable) {
            this.enableDynamicField = enable;
            return this;
        }

        /**
         * 设置默认搜索返回数量
         *
         * @param topK 返回数量
         * @return Builder 实例
         */
        public Builder defaultTopK(int topK) {
            this.defaultTopK = topK;
            return this;
        }

        /**
         * 设置是否自动创建集合
         *
         * @param auto 是否自动创建
         * @return Builder 实例
         */
        public Builder autoCreateCollection(boolean auto) {
            this.autoCreateCollection = auto;
            return this;
        }


        public Builder extFields(List<CreateCollectionReq.FieldSchema> extFields) {
            this.extFields = extFields;
            return this;
        }

        public Builder extField(CreateCollectionReq.FieldSchema extField) {
            if (this.extFields == null) {
                this.extFields = new ArrayList<>();
            }
            this.extFields.add(extField);
            return this;
        }

        public Builder primaryKeyType(DataType primaryKeyType) {
            this.primaryKeyType = primaryKeyType;
            return this;
        }

        /**
         * 构建 MilvusVectorStore 实例
         *
         * @return MilvusVectorStore 实例
         */
        public MilvusVectorStore build() {
            return new MilvusVectorStore(this);
        }
    }
}
