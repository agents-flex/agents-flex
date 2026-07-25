/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.store.chroma;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 Chroma REST API 的文档向量存储。
 *
 * <p>支持租户、数据库和集合三级定位，可按配置自动创建缺失资源。过滤条件转换为
 * Chroma 原生 {@code where} JSON，而不是拼接文本表达式。</p>
 */
public class ChromaVectorStore extends DocumentStore {

    private static final Logger logger = LoggerFactory.getLogger(ChromaVectorStore.class);
    private final String baseUrl;
    private final String defaultCollectionName;
    private final String tenant;
    private final String database;
    private final ChromaVectorStoreConfig config;
    private final ChromaConditionBuilder conditionBuilder = new ChromaConditionBuilder();
    private final AgentsFlexHttpClient agentsFlexHttpClient;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_INTERVAL_MS = 1000;
    private static final String TITLE_METADATA_KEY = "__agentsflex_document_title";

    private static final String BASE_API = "/api/v2";

    public ChromaVectorStore(ChromaVectorStoreConfig config) {
        Objects.requireNonNull(config, "ChromaVectorStoreConfig cannot be null");
        this.baseUrl = config.getBaseUrl();
        this.tenant = config.getTenant();
        this.database = config.getDatabase();
        this.defaultCollectionName = config.getCollectionName();
        this.config = config;
        // 创建并配置HttpClient实例
        this.agentsFlexHttpClient = AgentsFlexHttpClient.getDefault();

        // 验证配置的有效性
        validateConfig();

        // 如果配置了自动创建集合，检查并创建集合
        if (config.isAutoCreateCollection()) {
            try {
                // 确保租户和数据库存在
                ensureTenantAndDatabaseExists();
            } catch (Exception e) {
                logger.warn("Failed to ensure collection exists: {}. Will retry on first operation.", e.getMessage());
            }
        }
    }


    private void validateConfig() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("Base URL cannot be empty");
        }

        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }
    }

    /**
     * 确保租户和数据库存在，如果不存在则创建
     */
    private void ensureTenantAndDatabaseExists() {
        try {
            // 检查并创建租户
            if (tenant != null && !tenant.isEmpty()) {
                ensureTenantExists();

                // 检查并创建数据库（如果租户已设置）
                if (database != null && !database.isEmpty()) {
                    ensureDatabaseExists();
                }
            }
        } catch (Exception e) {
            logger.error("Error ensuring tenant and database exist", e);
        }
    }

    /**
     * 确保租户存在，如果不存在则创建
     */
    private void ensureTenantExists() throws IOException {
        String tenantUrl = baseUrl + BASE_API + "/tenants/" + tenant;
        Map<String, String> headers = createHeaders();

        try {
            // 尝试获取租户信息
            int statusCode = executeWithRetry(() -> agentsFlexHttpClient.getStatusCode(tenantUrl, headers));
            if (statusCode == 404) {
                // 如果获取失败，尝试创建租户
                logger.info("Creating tenant '{}' as it does not exist", tenant);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("name", tenant);

                String createTenantUrl = baseUrl + BASE_API + "/tenants";
                String jsonRequestBody = safeJsonSerialize(requestBody);

                String responseBody =
                    executeWithRetry(() -> agentsFlexHttpClient.post(createTenantUrl, headers, jsonRequestBody));
                logger.info("Successfully created tenant '{}'", tenant);
            } else {
                logger.debug("Successfully verified tenant '{}' exists", tenant);
            }
        } catch (IOException e) {
            logger.error("Can't connect url:" + tenantUrl);
        }
    }

    /**
     * 确保数据库存在，如果不存在则创建
     */
    private void ensureDatabaseExists() throws IOException {
        if (tenant == null || tenant.isEmpty()) {
            throw new IllegalStateException("Cannot create database without tenant");
        }

        String databaseUrl = baseUrl + BASE_API + "/tenants/" + tenant + "/databases/" + database;
        Map<String, String> headers = createHeaders();

        try {
            // 尝试获取数据库信息
            int statusCode = executeWithRetry(() -> agentsFlexHttpClient.getStatusCode(databaseUrl, headers));
            if (statusCode == 404) {
                logger.info("Creating database '{}' in tenant '{}' as it does not exist", database, tenant);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("name", database);

                String createDatabaseUrl = baseUrl + BASE_API + "/tenants/" + tenant + "/databases";
                String jsonRequestBody = safeJsonSerialize(requestBody);

                String responseBody =
                    executeWithRetry(() -> agentsFlexHttpClient.post(createDatabaseUrl, headers, jsonRequestBody));
                logger.info("Successfully created database '{}' in tenant '{}'", database, tenant);
            } else {
                logger.debug("Successfully verified database '{}' exists in tenant '{}'", database, tenant);
            }
        } catch (IOException e) {
            // 如果获取失败，尝试创建数据库
            logger.error("Can't connect url:" + databaseUrl);
        }
    }

    /**
     * 根据collectionName查询Collection ID
     */
    private String getCollectionId(String collectionName) throws IOException {
        String collectionsUrl = buildCollectionsUrl();
        Map<String, String> headers = createHeaders();

        String responseBody = executeWithRetry(() -> agentsFlexHttpClient.get(collectionsUrl, headers));
        if (responseBody == null) {
            throw new IOException("Failed to get collections, no response");
        }

        Object responseObj = parseJsonResponse(responseBody);
        List<Map<String, Object>> collections = new ArrayList<>();

        // 处理不同格式的响应
        if (responseObj instanceof Map) {
            Map<String, Object> responseMap = (Map<String, Object>) responseObj;
            if (responseMap.containsKey("collections") && responseMap.get("collections") instanceof List) {
                collections = (List<Map<String, Object>>) responseMap.get("collections");
            }
        } else if (responseObj instanceof List) {
            List<?> rawCollections = (List<?>) responseObj;
            for (Object item : rawCollections) {
                if (item instanceof Map) {
                    collections.add((Map<String, Object>) item);
                }
            }
        }

        // 查找指定名称的集合
        for (Map<String, Object> collection : collections) {
            if (collection.containsKey("name") && collectionName.equals(collection.get("name"))) {
                return collection.get("id").toString();
            }
        }

        throw new CollectionNotFoundException(collectionName);
    }

    private void createCollection(String collectionName) throws IOException {
        // 构建创建集合的API URL，包含tenant和database
        String createCollectionUrl = buildCollectionsUrl();
        Map<String, String> headers = createHeaders();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("name", collectionName);

        String jsonRequestBody = safeJsonSerialize(requestBody);

        String responseBody = executeWithRetry(() -> agentsFlexHttpClient.post(createCollectionUrl, headers, jsonRequestBody));
        if (responseBody == null) {
            throw new IOException("Failed to create collection: no response");
        }

        try {
            Object responseObj = parseJsonResponse(responseBody);

            Map<String, Object> responseMap = asResponseMap(responseObj, "create collection");
            if (responseMap.containsKey("error")) {
                throw new IOException("Failed to create collection: " + responseMap.get("error"));
            }

            logger.info("Collection '{}' created successfully", collectionName);
        } catch (Exception e) {
            throw new IOException("Failed to process collection creation response: " + e.getMessage(), e);
        }
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        Objects.requireNonNull(documents, "Documents cannot be null");

        if (documents.isEmpty()) {
            logger.debug("No documents to store");
            return StoreResult.success();
        }

        try {
            // 确保集合存在
            String collectionName = getCollectionName(options);
            ensureCollectionExists(collectionName);

            Map<String, Object> requestBody = createDocumentRequest(documents);

            String collectionId = getCollectionId(collectionName);

            // 构建包含tenant和database的完整URL
            String collectionUrl = buildCollectionUrl(collectionId, "upsert");

            Map<String, String> headers = createHeaders();

            String jsonRequestBody = safeJsonSerialize(requestBody);

            logger.debug("Storing {} documents to collection '{}'", documents.size(), collectionName);

            String responseBody = executeWithRetry(() -> agentsFlexHttpClient.post(collectionUrl, headers, jsonRequestBody));
            if (responseBody == null) {
                logger.error("Error storing documents: no response");
                return StoreResult.fail();
            }

            Object responseObj = parseJsonResponse(responseBody);

            Map<String, Object> responseMap = asResponseMap(responseObj, "store documents");
            if (responseMap.containsKey("error")) {
                String errorMsg = "Error storing documents: " + responseMap.get("error");
                logger.error(errorMsg);
                return StoreResult.fail();
            }

            logger.debug("Successfully stored {} documents", documents.size());
            return StoreResult.successWithIds(documents);
        } catch (Exception e) {
            logger.error("Error storing documents to Chroma", e);
            return StoreResult.fail();
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        Objects.requireNonNull(ids, "IDs cannot be null");

        if (ids.isEmpty()) {
            logger.debug("No IDs to delete");
            return StoreResult.success();
        }

        try {
            // 确保集合存在
            String collectionName = getCollectionName(options);
            ensureCollectionExists(collectionName);

            List<String> stringIds = ids.stream()
                .map(Object::toString)
                .collect(Collectors.toList());
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", stringIds);

            String collectionId = getCollectionId(collectionName);

            // 构建包含tenant和database的完整URL
            String collectionUrl = buildCollectionUrl(collectionId, "delete");

            Map<String, String> headers = createHeaders();

            String jsonRequestBody = safeJsonSerialize(requestBody);

            logger.debug("Deleting {} documents from collection '{}'", ids.size(), collectionName);

            String responseBody = executeWithRetry(() -> agentsFlexHttpClient.post(collectionUrl, headers, jsonRequestBody));
            if (responseBody == null) {
                logger.error("Error deleting documents: no response");
                return StoreResult.fail();
            }

            Object responseObj = parseJsonResponse(responseBody);

            Map<String, Object> responseMap = asResponseMap(responseObj, "delete documents");
            if (responseMap.containsKey("error")) {
                String errorMsg = "Error deleting documents: " + responseMap.get("error");
                logger.error(errorMsg);
                return StoreResult.fail();
            }

            logger.debug("Successfully deleted {} documents", ids.size());
            return StoreResult.success();
        } catch (Exception e) {
            logger.error("Error deleting documents from Chroma", e);
            return StoreResult.fail("Delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        Objects.requireNonNull(documents, "Documents cannot be null");

        if (documents.isEmpty()) {
            logger.debug("No documents to update");
            return StoreResult.success();
        }

        try {
            String collectionName = getCollectionName(options);
            ensureCollectionExists(collectionName);
            String collectionId = getCollectionId(collectionName);
            String collectionUrl = buildCollectionUrl(collectionId, "upsert");
            String request = safeJsonSerialize(createDocumentRequest(documents));
            String responseBody = executeWithRetry(() -> agentsFlexHttpClient.post(
                collectionUrl, createHeaders(), request));
            Map<String, Object> response = asResponseMap(parseJsonResponse(responseBody), "update documents");
            if (response.containsKey("error")) {
                return StoreResult.fail("Update failed: " + response.get("error"));
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception e) {
            logger.error("Error updating documents in Chroma", e);
            return StoreResult.fail("Update failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        Objects.requireNonNull(wrapper, "SearchWrapper cannot be null");

        try {
            // 确保集合存在
            String collectionName = getCollectionName(options);
            ensureCollectionExists(collectionName);

            int limit = wrapper.getMaxResults() > 0 ? wrapper.getMaxResults() : 10;

            Map<String, Object> requestBody = new HashMap<>();
            // 检查查询条件是否有效
            if (wrapper.getVector() == null && wrapper.getText() == null) {
                throw new IllegalArgumentException("Either vector or text must be provided for search");
            }

            // 设置查询向量
            if (wrapper.getVector() != null) {
                List<Double> queryEmbedding = wrapper.getVectorAsDoubleList();
                requestBody.put("query_embeddings", Collections.singletonList(queryEmbedding));
                logger.debug("Performing vector search with dimension: {}", queryEmbedding.size());
            } else if (wrapper.getText() != null) {
                requestBody.put("query_texts", Collections.singletonList(wrapper.getText()));
                logger.debug("Performing text search: {}", sanitizeLogString(wrapper.getText(), 100));
            }

            // 设置返回数量
            requestBody.put("n_results", limit);

            validateMinScore(wrapper.getMinScore());
            List<String> include = new ArrayList<>(Arrays.asList("documents", "metadatas", "distances"));
            if (wrapper.isOutputVector()) {
                include.add("embeddings");
            }
            requestBody.put("include", include);

            // 设置过滤条件
            if (wrapper.getCondition() != null) {
                Map<String, Object> where = conditionBuilder.build(wrapper.getCondition());
                requestBody.put("where", where);
                logger.debug("Search with filter condition: {}", where);
            }

            String collectionId = getCollectionId(collectionName);

            // 构建包含tenant和database的完整URL
            String collectionUrl = buildCollectionUrl(collectionId, "query");

            Map<String, String> headers = createHeaders();

            String jsonRequestBody = safeJsonSerialize(requestBody);

            String responseBody = executeWithRetry(() -> agentsFlexHttpClient.post(collectionUrl, headers, jsonRequestBody));
            if (responseBody == null) {
                throw new IOException("Error searching documents: no response");
            }


            Object responseObj = parseJsonResponse(responseBody);

            Map<String, Object> responseMap = asResponseMap(responseObj, "search documents");

            // 检查响应是否包含error字段
            if (responseMap.containsKey("error")) {
                throw new IOException("Error searching documents: " + responseMap.get("error"));
            }

            // 解析结果
            return parseSearchResults(responseMap, wrapper);
        } catch (Exception e) {
            throw e instanceof StoreException ? (StoreException) e
                : new StoreException("Search failed: " + e.getMessage(), e);
        }
    }

    /**
     * 支持直接使用向量数组和topK参数的搜索方法
     */
    public List<Document> searchInternal(double[] vector, int topK, StoreOptions options) {
        Objects.requireNonNull(vector, "Vector cannot be null");

        SearchWrapper wrapper = new SearchWrapper();
        wrapper.setVectorByNumbers(Arrays.stream(vector).boxed().collect(Collectors.toList()));
        wrapper.setMaxResults(topK > 0 ? topK : 10);
        return doSearch(wrapper, options);
    }

    private List<Document> parseSearchResults(Map<String, Object> responseMap, SearchWrapper wrapper) {
        try {
            List<String> ids = extractResultsFromNestedList(responseMap, "ids");
            List<String> documents = extractResultsFromNestedList(responseMap, "documents");
            List<Map<String, Object>> metadatas = extractResultsFromNestedList(responseMap, "metadatas");
            List<List<Double>> embeddings = extractResultsFromNestedList(responseMap, "embeddings");
            List<Double> distances = extractResultsFromNestedList(responseMap, "distances");

            if (ids == null || ids.isEmpty()) {
                logger.debug("No documents found in search results");
                return Collections.emptyList();
            }

            // 转换为Agents-Flex的Document格式
            List<Document> resultDocs = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Document doc = new Document();
                doc.setId(ids.get(i));

                if (documents != null && i < documents.size()) {
                    doc.setContent(documents.get(i));
                }

                if (metadatas != null && i < metadatas.size() && metadatas.get(i) != null) {
                    Map<String, Object> metadata = new HashMap<>(metadatas.get(i));
                    Object title = metadata.remove(TITLE_METADATA_KEY);
                    if (title != null) {
                        doc.setTitle(String.valueOf(title));
                    }
                    doc.setMetadataMap(filterMetadata(metadata, wrapper.getOutputFields()));
                }

                if (embeddings != null && i < embeddings.size() && embeddings.get(i) != null) {
                    doc.setVectorByNumbers(embeddings.get(i));
                }

                // 设置相似度分数（距离越小越相似）
                if (distances != null && i < distances.size()) {
                    float score = 1.0f - distances.get(i).floatValue();
                    // 确保分数在合理范围内
                    score = Math.max(0, Math.min(1, score));
                    doc.setScore(score);
                }
                if (wrapper.getMinScore() == null || doc.getScore() == null
                    || doc.getScore() >= wrapper.getMinScore()) {
                    resultDocs.add(doc);
                }
            }

            logger.debug("Found {} documents in search results", resultDocs.size());
            return resultDocs;
        } catch (Exception e) {
            throw new StoreException("Failed to parse Chroma search results: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> extractResultsFromNestedList(Map<String, Object> responseMap, String key) {
        try {
            if (!responseMap.containsKey(key)) {
                return null;
            }

            List<?> outerList = (List<?>) responseMap.get(key);
            if (outerList == null || outerList.isEmpty()) {
                return null;
            }

            // Chroma返回的结果是嵌套列表，第一个元素是当前查询的结果
            return (List<T>) outerList.get(0);
        } catch (Exception e) {
            logger.warn("Failed to extract '{}' from response: {}", key, e.getMessage());
            return null;
        }
    }

    private Map<String, String> createHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            headers.put("X-Chroma-Token", config.getApiKey());
        }

        // 添加租户和数据库信息（如果配置了）
        if (tenant != null && !tenant.isEmpty()) {
            headers.put("X-Chroma-Tenant", tenant);
        }

        if (database != null && !database.isEmpty()) {
            headers.put("X-Chroma-Database", database);
        }

        return headers;
    }

    private Map<String, Object> createDocumentRequest(List<Document> documents) {
        List<String> ids = new ArrayList<>(documents.size());
        List<List<Double>> embeddings = new ArrayList<>(documents.size());
        List<Map<String, Object>> metadatas = new ArrayList<>(documents.size());
        List<String> contents = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document == null || document.getId() == null) {
                throw new IllegalArgumentException("Chroma documents and document IDs cannot be null");
            }
            ids.add(String.valueOf(document.getId()));
            embeddings.add(document.getVectorAsDoubleList());
            Map<String, Object> metadata = document.getMetadataMap() == null
                ? new HashMap<>() : new HashMap<>(document.getMetadataMap());
            if (metadata.containsKey(TITLE_METADATA_KEY)) {
                throw new IllegalArgumentException("Metadata key is reserved: " + TITLE_METADATA_KEY);
            }
            if (document.getTitle() != null) {
                metadata.put(TITLE_METADATA_KEY, document.getTitle());
            }
            metadatas.add(metadata);
            contents.add(document.getContent());
        }
        Map<String, Object> request = new HashMap<>();
        request.put("ids", ids);
        request.put("embeddings", embeddings);
        request.put("metadatas", metadatas);
        request.put("documents", contents);
        return request;
    }

    private Map<String, Object> filterMetadata(Map<String, Object> metadata, List<String> outputFields) {
        if (outputFields == null) {
            return metadata;
        }
        Map<String, Object> filtered = new HashMap<>();
        for (String field : outputFields) {
            String metadataField = field;
            if (metadataField.startsWith("metadataMap.")) {
                metadataField = metadataField.substring("metadataMap.".length());
            } else if (metadataField.startsWith("metadata.")) {
                metadataField = metadataField.substring("metadata.".length());
            }
            if (metadata.containsKey(metadataField)) {
                filtered.put(metadataField, metadata.get(metadataField));
            }
        }
        return filtered;
    }

    private void validateMinScore(Double minScore) {
        if (minScore != null && (minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asResponseMap(Object response, String operation) throws IOException {
        if (!(response instanceof Map)) {
            throw new IOException("Invalid Chroma response while attempting to " + operation);
        }
        return (Map<String, Object>) response;
    }

    private <T> T executeWithRetry(HttpOperation<T> operation) throws IOException {
        int attempts = 0;
        IOException lastException = null;

        while (attempts < MAX_RETRIES) {
            try {
                attempts++;
                return operation.execute();
            } catch (IOException | UncheckedIOException e) {
                IOException ioException = e instanceof UncheckedIOException
                    ? ((UncheckedIOException) e).getCause()
                    : (IOException) e;
                lastException = ioException;

                // 如果是最后一次尝试，则抛出异常
                if (attempts >= MAX_RETRIES) {
                    throw new IOException("Operation failed after " + MAX_RETRIES + " attempts: "
                        + ioException.getMessage(), ioException);
                }

                // 记录重试信息
                logger.warn("Operation failed (attempt {} of {}), retrying in {}ms: {}",
                    attempts, MAX_RETRIES, RETRY_INTERVAL_MS, ioException.getMessage());

                // 等待一段时间后重试
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Retry interrupted", ie);
                }
            }
        }

        // 这一行理论上不会执行到，但为了编译器满意
        throw lastException != null ? lastException : new IOException("Operation failed without exception");
    }

    private String safeJsonSerialize(Map<String, Object> map) {
        // 使用标准的JSON序列化，但在实际应用中可以添加更多的安全检查
        try {
            return new com.google.gson.Gson().toJson(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body to JSON", e);
        }
    }

    private Object parseJsonResponse(String json) {
        try {
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            // Check if JSON starts with [ indicating an array
            if (json.trim().startsWith("[")) {
                return new com.google.gson.Gson().fromJson(json, List.class);
            } else {
                // Otherwise assume it's an object
                return new com.google.gson.Gson().fromJson(json, Map.class);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON response: " + json, e);
        }
    }

    private String sanitizeLogString(String input, int maxLength) {
        if (input == null) {
            return null;
        }

        String sanitized = input.replaceAll("[\n\r]", " ");
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) + "..." : sanitized;
    }

    private String getCollectionName(StoreOptions options) {
        return options != null
            ? options.getCollectionNameOrDefault(defaultCollectionName)
            : defaultCollectionName;
    }

    /**
     * 构建特定集合操作的URL，包含tenant和database
     */
    private String buildCollectionUrl(String collectionId, String operation) {
        StringBuilder urlBuilder = new StringBuilder(baseUrl).append(BASE_API);

        if (tenant != null && !tenant.isEmpty()) {
            urlBuilder.append("/tenants/").append(encodePathSegment(tenant));

            if (database != null && !database.isEmpty()) {
                urlBuilder.append("/databases/").append(encodePathSegment(database));
            }
        }

        urlBuilder.append("/collections/").append(encodePathSegment(collectionId))
            .append("/").append(encodePathSegment(operation));
        return urlBuilder.toString();
    }

    /**
     * 关闭存储。当前 HTTP 客户端使用共享连接池，因此这里只保留资源清理扩展点。
     */
    public void close() {
        // HTTP 客户端使用共享连接池，此处无需主动关闭。
        logger.info("Chroma client closed");
    }

    /**
     * 确保集合存在，如果不存在则创建
     */
    private void ensureCollectionExists(String collectionName) throws IOException {
        String finalCollectionName =
            StringUtil.hasText(collectionName) ? collectionName : defaultCollectionName;

        try {
            // 尝试获取默认集合ID，如果能获取到则说明集合存在
            getCollectionId(finalCollectionName);
            logger.debug("Collection '{}' exists", finalCollectionName);
        } catch (CollectionNotFoundException e) {
            if (!config.isAutoCreateCollection()) {
                throw e;
            }
            logger.info("Collection '{}' does not exist, creating...", finalCollectionName);
            try {
                createCollection(finalCollectionName);
            } catch (IOException createFailure) {
                try {
                    getCollectionId(finalCollectionName);
                } catch (IOException stillMissing) {
                    throw createFailure;
                }
            }
            logger.info("Collection '{}' created successfully", finalCollectionName);
        }
    }

    /**
     * 构建集合列表URL，包含tenant和database
     */
    private String buildCollectionsUrl() {
        StringBuilder urlBuilder = new StringBuilder(baseUrl).append(BASE_API);

        if (tenant != null && !tenant.isEmpty()) {
            urlBuilder.append("/tenants/").append(encodePathSegment(tenant));

            if (database != null && !database.isEmpty()) {
                urlBuilder.append("/databases/").append(encodePathSegment(database));
            }
        }

        urlBuilder.append("/collections");
        return urlBuilder.toString();
    }

    private String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Chroma URL path segment", e);
        }
    }

    private static class CollectionNotFoundException extends IOException {
        CollectionNotFoundException(String collectionName) {
            super("Collection not found: " + collectionName);
        }
    }

    /**
     * 函数式接口，用于封装HTTP操作以支持重试
     */
    private interface HttpOperation<T> {
        T execute() throws IOException;
    }
}
