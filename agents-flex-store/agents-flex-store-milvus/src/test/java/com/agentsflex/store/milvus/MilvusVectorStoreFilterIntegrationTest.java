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
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MilvusVectorStoreFilterIntegrationTest {

    private static final String ENDPOINT = "http://127.0.0.1:19530";

    private String collectionName;
    private MilvusVectorStoreConfig config;
    private MilvusVectorStore store;
    private MilvusClientV2 client;

    @Before
    public void setUp() throws InterruptedException {
        collectionName = "agents_flex_filter_" + UUID.randomUUID().toString().replace("-", "");
        config = MilvusVectorStoreConfig.builder()
            .endpoint(ENDPOINT)
            .defaultCollectionName(collectionName)
            .defaultDimension(2)
            .metricType("COSINE")
            .consistencyLevel("Strong")
            .enableDynamicField(true)
            .build();
        store = MilvusVectorStore.create(config);
        try {
            client = new MilvusClientV2(ConnectConfig.builder().uri(ENDPOINT).build());
        } catch (Exception e) {
            Assume.assumeNoException("Milvus is required for this integration test", e);
        }

        Document docA = document("doc-a", "content-a", new float[]{1.0f, 0.0f}, "AI", 10);
        Document docB = document("doc-b", "content-b", new float[]{0.0f, 1.0f}, "ML", 20);
        Document docC = document("doc-c", "content-c", new float[]{-1.0f, 0.0f}, "NLP", 30);
        StoreResult result = store.store(Arrays.asList(docA, docB, docC));
        assertTrue(result.toString(), result.isSuccess());
        assertEquals(3, result.getIds().size());
        assertEquals(3, directSearch().size());
        assertEquals(3, store.search(search()).size());
    }

    @After
    public void tearDown() {
        if (collectionName == null || client == null) {
            return;
        }
        try {
            client.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
        } finally {
            client.close();
        }
    }

    @Test
    public void shouldSupportEqualityFilter() {
        SearchWrapper search = search();
        search.eq("category", "AI");

        List<Document> documents = store.search(search);

        assertEquals(1, documents.size());
        assertEquals("doc-a", documents.get(0).getId());
    }

    @Test
    public void shouldSupportInFilter() {
        SearchWrapper search = search();
        search.in("category", Arrays.asList("AI", "ML"));

        List<Document> documents = store.search(search);

        assertEquals(2, documents.size());
    }

    @Test
    public void shouldSupportNotInFilter() {
        SearchWrapper search = search();
        search.nin("category", Arrays.asList("NLP"));

        List<Document> documents = store.search(search);

        assertEquals(2, documents.size());
    }

    @Test
    public void shouldSupportNumericComparison() {
        SearchWrapper search = search();
        search.ge("views", 20);

        List<Document> documents = store.search(search);

        assertEquals(2, documents.size());
    }

    @Test
    public void shouldSupportBetweenFilter() {
        SearchWrapper search = search();
        search.between("views", 15, 25);

        List<Document> documents = store.search(search);

        assertEquals(1, documents.size());
        assertEquals("doc-b", documents.get(0).getId());
    }

    @Test
    public void shouldSupportUnaryNotFilter() {
        SearchWrapper search = search()
            .not(group -> group.eq("category", "ML"));

        List<Document> documents = store.search(search);

        assertEquals(2, documents.size());
    }

    @Test
    public void shouldApplyMinScore() {
        SearchWrapper search = search();
        search.setMinScore(0.5d);

        List<Document> documents = store.search(search);

        assertEquals(1, documents.size());
        assertEquals("doc-a", documents.get(0).getId());
    }

    @Test
    public void shouldReturnVectorOnlyWhenRequested() {
        SearchWrapper withoutVector = search();
        withoutVector.setMaxResults(1);
        List<Document> documentsWithoutVector = store.search(withoutVector);
        assertEquals(1, documentsWithoutVector.size());
        assertNull(documentsWithoutVector.get(0).getVector());

        SearchWrapper withVector = search();
        withVector.setMaxResults(1);
        withVector.setOutputVector(true);
        List<Document> documents = store.search(withVector);

        assertEquals(1, documents.size());
        assertNotNull(documents.get(0).getVector());
        assertEquals(2, documents.get(0).getVector().length);
    }

    @Test
    public void shouldEscapeStringIdsWhenSearchingAndDeleting() {
        String id = "doc-\"quoted\\value";
        StoreResult storeResult = store.store(document(id, "quoted", new float[]{1.0f, 0.0f}, "AI", 40));
        assertTrue(storeResult.toString(), storeResult.isSuccess());

        SearchWrapper byId = search();
        byId.eq("id", id);
        assertEquals(1, store.search(byId).size());

        StoreResult deleteResult = store.delete(id);
        assertTrue(deleteResult.toString(), deleteResult.isSuccess());
        assertEquals(0, store.search(byId).size());
    }

    @Test
    public void shouldLoadAnExistingUnloadedCollection() {
        client.releaseCollection(ReleaseCollectionReq.builder()
            .collectionName(collectionName)
            .build());

        MilvusVectorStore anotherStore = new MilvusVectorStore.Builder()
            .connectConfig(ConnectConfig.builder().uri(ENDPOINT).build())
            .defaultCollectionName(collectionName)
            .defaultDimension(2)
            .metricType(IndexParam.MetricType.COSINE)
            .consistencyLevel(ConsistencyLevel.STRONG)
            .autoCreateCollection(false)
            .build();

        assertEquals(3, anotherStore.search(search()).size());
    }

    @Test(expected = StoreException.class)
    public void shouldSurfaceInvalidFilterErrors() {
        SearchWrapper invalidSearch = search();
        invalidSearch.eq("category", new Object());

        store.search(invalidSearch);
    }

    private SearchWrapper search() {
        SearchWrapper search = new SearchWrapper();
        search.setVector(new float[]{1.0f, 0.0f});
        search.setMaxResults(10);
        return search;
    }

    private List<SearchResp.SearchResult> directSearch() {
        SearchResp response = client.search(SearchReq.builder()
            .collectionName(collectionName)
            .data(Arrays.asList(new FloatVec(new float[]{1.0f, 0.0f})))
            .limit(10)
            .consistencyLevel(ConsistencyLevel.STRONG)
            .outputFields(Arrays.asList("content", "category", "views"))
            .build());
        return response.getSearchResults().get(0);
    }

    private Document document(String id, String content, float[] vector, String category, int views) {
        Document document = Document.of(content);
        document.setId(id);
        document.setVector(vector);
        document.putMetadata("category", category);
        document.putMetadata("views", views);
        return document;
    }
}
