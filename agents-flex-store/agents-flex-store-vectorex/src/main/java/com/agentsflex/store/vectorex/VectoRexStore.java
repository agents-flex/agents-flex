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
package com.agentsflex.store.vectorex;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import io.github.javpower.vectorexclient.VectorRexClient;
import io.github.javpower.vectorexclient.builder.QueryBuilder;
import io.github.javpower.vectorexclient.entity.MetricType;
import io.github.javpower.vectorexclient.entity.ScalarField;
import io.github.javpower.vectorexclient.entity.VectoRexEntity;
import io.github.javpower.vectorexclient.entity.VectorFiled;
import io.github.javpower.vectorexclient.req.CollectionDataAddReq;
import io.github.javpower.vectorexclient.req.CollectionDataDelReq;
import io.github.javpower.vectorexclient.req.VectoRexCollectionReq;
import io.github.javpower.vectorexclient.res.DbData;
import io.github.javpower.vectorexclient.res.ServerResponse;
import io.github.javpower.vectorexclient.res.VectorSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class VectoRexStore extends DocumentStore {

    private final VectoRexStoreConfig config;
    private final VectorRexClient client;
    private final String defaultCollectionName;
    private boolean isCreateCollection = false;
    private static final Logger logger = LoggerFactory.getLogger(VectoRexStore.class);

    public VectoRexStore(VectoRexStoreConfig config) {
        this.config = config;
        this.defaultCollectionName = config.getDefaultCollectionName();
        this.client = new VectorRexClient(config.getUri(), config.getUsername(), config.getPassword());
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> dict = new HashMap<>();
            dict.put("id", String.valueOf(doc.getId()));
            dict.put("content", doc.getContent());
            dict.put("vector", doc.getVectorAsList());
            data.add(dict);
        }
        String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
        if (config.isAutoCreateCollection() && !isCreateCollection) {
            ServerResponse<List<VectoRexEntity>> collections = client.getCollections();
            if (collections.getData() == null || collections.getData().stream().noneMatch(e -> e.getCollectionName().equals(collectionName))) {
                createCollection(collectionName);
            } else {
                isCreateCollection = true;
            }
        }
        for (Map<String, Object> map : data) {
            CollectionDataAddReq req = CollectionDataAddReq.builder().collectionName(collectionName).metadata(map).build();
            client.addCollectionData(req);
        }
        return StoreResult.successWithIds(documents);
    }


    private Boolean createCollection(String collectionName) {
        List<ScalarField> scalarFields = new ArrayList();
        ScalarField id = ScalarField.builder().name("id").isPrimaryKey(true).build();
        ScalarField content = ScalarField.builder().name("content").isPrimaryKey(false).build();
        scalarFields.add(id);
        scalarFields.add(content);
        List<VectorFiled> vectorFiles = new ArrayList();
        VectorFiled vector = VectorFiled.builder().name("vector").metricType(MetricType.FLOAT_COSINE_DISTANCE).dimensions(this.getEmbeddingModel().dimensions()).build();
        vectorFiles.add(vector);
        ServerResponse<Void> response = client.createCollection(VectoRexCollectionReq.builder().collectionName(collectionName).scalarFields(scalarFields).vectorFileds(vectorFiles).build());
        return response.isSuccess();
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        for (Object id : ids) {
            CollectionDataDelReq req = CollectionDataDelReq.builder().collectionName(options.getCollectionNameOrDefault(defaultCollectionName)).id((String) id).build();
            ServerResponse<Void> response = client.deleteCollectionData(req);
            if (!response.isSuccess()) {
                return StoreResult.fail();
            }
        }
        return StoreResult.success();

    }

    @Override
    public List<Document> doSearch(SearchWrapper searchWrapper, StoreOptions options) {
        ServerResponse<List<VectorSearchResult>> response = client.queryCollectionData(QueryBuilder.lambda(options.getCollectionNameOrDefault(defaultCollectionName))
            .vector("vector", Collections.singletonList(searchWrapper.getVectorAsList())).topK(searchWrapper.getMaxResults()));
        if (!response.isSuccess()) {
            logger.error("Error searching in VectoRex", response.getMsg());
            return Collections.emptyList();
        }
        List<VectorSearchResult> data = response.getData();
        List<Document> documents = new ArrayList<>();
        for (VectorSearchResult result : data) {
            DbData dd = result.getData();
            Map<String, Object> metadata = dd.getMetadata();
            Document doc = new Document();
            doc.setId(result.getId());
            doc.setContent((String) metadata.get("content"));
            Object vectorObj = metadata.get("vector");
            if (vectorObj instanceof List) {
                //noinspection unchecked
                doc.setVector((List<Float>) vectorObj);
            }
            documents.add(doc);
        }
        return documents;
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> dict = new HashMap<>();
            dict.put("id", String.valueOf(doc.getId()));
            dict.put("content", doc.getContent());
            dict.put("vector", doc.getVectorAsList());
            data.add(dict);
        }
        String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
        for (Map<String, Object> map : data) {
            CollectionDataAddReq req = CollectionDataAddReq.builder().collectionName(collectionName).metadata(map).build();
            client.updateCollectionData(req);
        }
        return StoreResult.successWithIds(documents);
    }

    public VectorRexClient getClient() {
        return client;
    }

}
