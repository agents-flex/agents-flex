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
package com.agentsflex.store.opensearch;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.store.elasticsearch.ElasticSearchVectorStore;
import com.agentsflex.store.elasticsearch.ElasticSearchVectorStoreConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author songyinyin
 */
public class ElasticSearchVectorStoreTest {

    private static ElasticSearchVectorStore getVectorStore() {
        ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
        // config.setApiKey("bmtXRVNaRUJNMEZXZzMzcnNvSXk6MlNMVmFnT0hRVVNUSmN3UXpoNWp4Zw==");
        config.setUsername("elastic");
        config.setPassword("Dd2024a10");
        ElasticSearchVectorStore store = new ElasticSearchVectorStore(config);
        store.setEmbeddingModel(new EmbeddingModel() {
            @Override
            public VectorData embed(Document document, EmbeddingOptions options) {
                VectorData vectorData = new VectorData();
                vectorData.setVector(new float[]{0, 0});
                return vectorData;
            }
        });
        return store;
    }

    @Test(expected = StoreException.class)
    public void test01() {
        ElasticSearchVectorStore store = getVectorStore();

        // https://opensearch.org/docs/latest/search-plugins/vector-search/#example
        List<Document> list = new ArrayList<>();
        Document doc1 = new Document();
        doc1.setId(1);
        doc1.setContent("test1");
        doc1.setVector(new float[]{5.2f, 4.4f});
        list.add(doc1);
        Document doc2 = new Document();
        doc2.setId(2);
        doc2.setContent("test2");
        doc2.setVector(new float[]{5.2f, 3.9f});
        list.add(doc2);
        Document doc3 = new Document();
        doc3.setId(3);
        doc3.setContent("test3");
        doc3.setVector(new float[]{4.9f, 3.4f});
        list.add(doc3);
        Document doc4 = new Document();
        doc4.setId(4);
        doc4.setContent("test4");
        doc4.setVector(new float[]{4.2f, 4.6f});
        list.add(doc4);
        Document doc5 = new Document();
        doc5.setId(5);
        doc5.setContent("test5");
        doc5.setVector(new float[]{3.3f, 4.5f});
        list.add(doc5);
        store.doStore(list, StoreOptions.DEFAULT);

        // 可能要等一会 才能查出结果
        SearchWrapper searchWrapper = new SearchWrapper();
        searchWrapper.setVector(new float[]{5, 4});
        searchWrapper.setMaxResults(3);
        List<Document> documents = store.doSearch(searchWrapper, StoreOptions.DEFAULT);
        for (Document document : documents) {
            System.out.printf("id=%s, content=%s, vector=%s, metadata=%s\n",
                document.getId(), document.getContent(), Arrays.toString(document.getVector()), document.getMetadataMap());
        }

    }
}
