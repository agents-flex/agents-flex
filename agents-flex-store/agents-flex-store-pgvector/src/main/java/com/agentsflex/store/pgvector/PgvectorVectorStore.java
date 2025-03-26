/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.store.pgvector;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class PgvectorVectorStore extends DocumentStore {
    private static final Logger logger = LoggerFactory.getLogger(PgvectorVectorStore.class);
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;
    private final PGSimpleDataSource dataSource;
    private final String defaultCollectionName;
    private final PgvectorVectorStoreConfig config;


    public PgvectorVectorStore(PgvectorVectorStoreConfig config) {
        dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[]{config.getHost() + ":" + config.getPort()});
        dataSource.setUser(config.getUsername());
        dataSource.setPassword(config.getPassword());
        dataSource.setDatabaseName(config.getDatabaseName());
        if (!config.getProperties().isEmpty()) {
            config.getProperties().forEach((k, v) -> {
                try {
                    dataSource.setProperty(k, v);
                } catch (SQLException e) {
                    logger.error("set pg property error", e);
                }
            });
        }

        this.defaultCollectionName = config.getDefaultCollectionName();
        this.config = config;

        // 异步初始化数据库
        new Thread(this::initDb).start();
    }

    public void initDb() {
        // 启动的时候初始化向量表, 需要数据库支持pgvector插件
        // pg管理员需要在对应的库上执行 CREATE EXTENSION IF NOT EXISTS vector;
        if (config.isAutoCreateCollection()) {
            createCollection(defaultCollectionName);
        }
    }

    private Connection getConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    @Override
    public StoreResult storeInternal(List<Document> documents, StoreOptions options) {

        // 表名
        String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);

        try (Connection connection = getConnection()) {
            PreparedStatement pstmt = connection.prepareStatement("insert into " + collectionName + " (id, content, vector, metadata) values (?, ?, ?, ?::jsonb)");
            for (Document doc : documents) {
                Map<String, Object> metadatas = doc.getMetadataMap();
                JSONObject jsonObject = JSON.parseObject(JSON.toJSONBytes(metadatas == null ? Collections.EMPTY_MAP : metadatas));
                pstmt.setString(1, String.valueOf(doc.getId()));
                pstmt.setString(2, doc.getContent());
                pstmt.setObject(3, PgvectorUtil.toPgVector(doc.getVector()));
                pstmt.setString(4, jsonObject.toString());
                pstmt.addBatch();
            }

            pstmt.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            logger.error("store vector error", e);
            return StoreResult.fail();
        }
        return StoreResult.successWithIds(documents);
    }

    private Boolean createCollection(String collectionName) {
        try (Connection connection = getConnection()) {
            try (CallableStatement statement = connection.prepareCall("CREATE TABLE IF NOT EXISTS " + collectionName +
                " (id varchar(100) PRIMARY KEY, content text, vector vector(" + config.getVectorDimension() + "), metadata jsonb)")) {
                statement.execute();
            }

            // 默认情况下，pgvector 执行精确的最近邻搜索，从而提供完美的召回率. 可以通过索引来修改 pgvector 的搜索方式，以获得更好的性能。
            // By default, pgvector performs exact nearest neighbor search, which provides perfect recall.
            if (config.isUseHnswIndex()) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("CREATE INDEX IF NOT EXISTS " + collectionName + "_vector_idx ON " + collectionName +
                        " USING hnsw (vector vector_cosine_ops)");
                }
            }

        } catch (SQLException e) {
            logger.error("create collection error", e);
            return false;
        }

        return true;
    }

    @Override
    public StoreResult deleteInternal(Collection<?> ids, StoreOptions options) {
        StringBuilder sql = new StringBuilder("DELETE FROM " + options.getCollectionNameOrDefault(defaultCollectionName) + " WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append("?");
            if (i < ids.size() - 1) {
                sql.append(",");
            }
        }
        sql.append(")");

        try (Connection connection = getConnection()) {
            PreparedStatement pstmt = connection.prepareStatement(sql.toString());
            ArrayList<?> list = new ArrayList<>(ids);
            for (int i = 0; i < list.size(); i++) {
                pstmt.setString(i + 1, (String) list.get(i));
            }

            pstmt.executeUpdate();
            connection.commit();
        } catch (Exception e) {
            logger.error("delete document error: " + e, e);
            return StoreResult.fail();
        }

        return StoreResult.success();

    }

    @Override
    public List<Document> searchInternal(SearchWrapper searchWrapper, StoreOptions options) {
        StringBuilder sql = new StringBuilder("select ");
        if (searchWrapper.isOutputVector()) {
            sql.append("id, vector, content, metadata");
        } else {
            sql.append("id,  content, metadata");
        }

        sql.append(" from ").append(options.getCollectionNameOrDefault(defaultCollectionName));
        sql.append(" where vector <=> ? < ? order by vector <=> ? LIMIT ?");

        try (Connection connection = getConnection()){
            // 使用余弦距离计算最相似的文档
            PreparedStatement stmt = connection.prepareStatement(sql.toString());

            PGobject vector = PgvectorUtil.toPgVector(searchWrapper.getVector());
            stmt.setObject(1, vector);
            stmt.setObject(2, Optional.ofNullable(searchWrapper.getMinScore()).orElse(DEFAULT_SIMILARITY_THRESHOLD));
            stmt.setObject(3, vector);
            stmt.setObject(4, searchWrapper.getMaxResults());

            ResultSet resultSet = stmt.executeQuery();
            List<Document> documents = new ArrayList<>();
            while (resultSet.next()) {
                Document doc = new Document();
                doc.setId(resultSet.getString("id"));
                doc.setContent(resultSet.getString("content"));
                doc.addMetadata(JSON.parseObject(resultSet.getString("metadata")));

                if (searchWrapper.isOutputVector()) {
                    String vectorStr = resultSet.getString("vector");
                    doc.setVector(PgvectorUtil.fromPgVector(vectorStr));
                }

                documents.add(doc);
            }

            return documents;
        } catch (Exception e) {
            logger.error("Error searching in pgvector", e);
            return Collections.emptyList();
        }
    }

    @Override
    public StoreResult updateInternal(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }

        StringBuilder sql = new StringBuilder("UPDATE " + options.getCollectionNameOrDefault(defaultCollectionName) + " SET ");
        sql.append("content = ?, vector = ?, metadata = ?::jsonb WHERE id = ?");
        try (Connection connection = getConnection()) {
            PreparedStatement pstmt = connection.prepareStatement(sql.toString());
            for (Document doc : documents) {
                Map<String, Object> metadatas = doc.getMetadataMap();
                JSONObject metadataJson = JSON.parseObject(JSON.toJSONBytes(metadatas == null ? Collections.EMPTY_MAP : metadatas));
                pstmt.setString(1, doc.getContent());
                pstmt.setObject(2, PgvectorUtil.toPgVector(doc.getVector()));
                pstmt.setString(3, metadataJson.toString());
                pstmt.setString(4, String.valueOf(doc.getId()));
                pstmt.addBatch();
            }

            pstmt.executeUpdate();
            connection.commit();
        } catch (Exception e) {
            logger.error("Error update in pgvector", e);
            return StoreResult.fail();
        }
        return StoreResult.successWithIds(documents);
    }
}
