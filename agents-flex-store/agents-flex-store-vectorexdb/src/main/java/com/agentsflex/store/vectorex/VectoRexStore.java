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
package com.agentsflex.store.vectorex;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.util.CollectionUtil;
import com.google.common.collect.Lists;
import io.github.javpower.vectorex.keynote.core.DbData;
import io.github.javpower.vectorex.keynote.core.VectorData;
import io.github.javpower.vectorex.keynote.core.VectorSearchResult;
import io.github.javpower.vectorex.keynote.model.MetricType;
import io.github.javpower.vectorex.keynote.model.VectorFiled;
import io.github.javpower.vectorexcore.VectoRexClient;
import io.github.javpower.vectorexcore.entity.KeyValue;
import io.github.javpower.vectorexcore.entity.ScalarField;
import io.github.javpower.vectorexcore.entity.VectoRexEntity;

import java.util.*;

public class VectoRexStore extends DocumentStore {

    private final VectoRexStoreConfig config;
    private final VectoRexClient client;
    private final String defaultCollectionName;
    private boolean isCreateCollection=false;

    public VectoRexStore(VectoRexStoreConfig config) {
        this.config = config;
        this.defaultCollectionName=config.getDefaultCollectionName();
        this.client = new VectoRexClient(config.getUri());
    }
    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        List<DbData> data=new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> dict=new HashMap<>();
            dict.put("id",String.valueOf(doc.getId()));
            dict.put("content", doc.getContent());
            dict.put("vector", doc.getVector());
            DbData dbData=new DbData();
            dbData.setId(String.valueOf(doc.getId()));
            dbData.setMetadata(dict);
            VectorData vd=new VectorData(dbData.getId(),doc.getVector());
            vd.setName("vector");
            dbData.setVectorFiled(Lists.newArrayList(vd));
            data.add(dbData);
        }
        String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
        if(config.isAutoCreateCollection()&&!isCreateCollection){
            List<VectoRexEntity> collections = client.getCollections();
            if(CollectionUtil.noItems(collections)||collections.stream().noneMatch(e -> e.getCollectionName().equals(collectionName))){
                createCollection(collectionName);
            }else {
                isCreateCollection=true;
            }
        }
        if(CollectionUtil.hasItems(data)){
            client.getStore(collectionName).saveAll(data);
        }
        return StoreResult.successWithIds(documents);
    }


    private void createCollection(String collectionName) {
        VectorFiled vectorFiled = new VectorFiled();
        vectorFiled.setDimensions(this.getEmbeddingModel().dimensions());
        vectorFiled.setName("vector");
        vectorFiled.setMetricType(MetricType.FLOAT_COSINE_DISTANCE);
        VectoRexEntity entity=new VectoRexEntity();
        entity.setCollectionName(collectionName);
        List<KeyValue<String, VectorFiled>> vectorFiles=new ArrayList<>();
        vectorFiles.add(new KeyValue<>("vector",vectorFiled));
        List<KeyValue<String, ScalarField>> scalarFields=new ArrayList<>();
        ScalarField id = new ScalarField();
        id.setName("id");
        id.setIsPrimaryKey(true);
        scalarFields.add(new KeyValue<>("id",id));
        ScalarField content = new ScalarField();
        content.setName("content");
        content.setIsPrimaryKey(false);
        scalarFields.add(new KeyValue<>("content",content));
        entity.setVectorFileds(vectorFiles);
        entity.setScalarFields(scalarFields);
        client.createCollection(entity);
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        client.getStore(options.getCollectionNameOrDefault(defaultCollectionName)).deleteAll((List<String>) ids);
        return StoreResult.success();

    }

    @Override
    public List<Document> doSearch(SearchWrapper searchWrapper, StoreOptions options) {
        List<VectorSearchResult> data = client.getStore(options.getCollectionNameOrDefault(defaultCollectionName)).search("vector", searchWrapper.getVectorAsList(), searchWrapper.getMaxResults(), null);
        List<Document> documents = new ArrayList<>();
        for (VectorSearchResult result : data) {
            DbData dd = result.getData();
            Map<String, Object> metadata = dd.getMetadata();
            Document doc=new Document();
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
        for (Document doc : documents) {
            Map<String, Object> dict=new HashMap<>();
            dict.put("id",String.valueOf(doc.getId()));
            dict.put("content", doc.getContent());
            dict.put("vector", doc.getVector());
            DbData dbData=new DbData();
            dbData.setId(String.valueOf(doc.getId()));
            dbData.setMetadata(dict);
            VectorData vd=new VectorData(dbData.getId(),doc.getVector());
            vd.setName("vector");
            dbData.setVectorFiled(Lists.newArrayList(vd));
            client.getStore(options.getCollectionNameOrDefault(defaultCollectionName)).update(dbData);
        }
        return StoreResult.successWithIds(documents);
    }

    public VectoRexClient getClient() {
        return client;
    }

}
