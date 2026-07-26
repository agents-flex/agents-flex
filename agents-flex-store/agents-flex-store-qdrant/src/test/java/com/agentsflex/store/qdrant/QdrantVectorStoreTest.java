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
package com.agentsflex.store.qdrant;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class QdrantVectorStoreTest {

    private final List<QdrantVectorStore> stores = new ArrayList<>();
    private final List<String> collections = new ArrayList<>();

    @Before
    public void requireRealQdrant() throws Exception {
        Assume.assumeTrue("Enable with -Dagentsflex.qdrant.integration=true",
            Boolean.getBoolean("agentsflex.qdrant.integration"));
        QdrantVectorStore probe = newStore(true);
        probe.getClient().listCollectionsAsync().get();
    }

    @After
    public void cleanUp() throws Exception {
        if (!stores.isEmpty()) {
            QdrantVectorStore cleaner = stores.get(0);
            for (String collection : collections) {
                if (cleaner.getClient().collectionExistsAsync(collection).get()) {
                    cleaner.getClient().deleteCollectionAsync(collection).get();
                }
            }
        }
        for (QdrantVectorStore store : stores) {
            store.close();
        }
    }

    @Test
    public void shouldKeepCollectionsIsolatedForSameStringId() throws Exception {
        QdrantVectorStore store = newStore(true);
        StoreOptions first = StoreOptions.ofCollectionName(collectionName());
        StoreOptions second = StoreOptions.ofCollectionName(collectionName());

        assertTrue(store.doStore(Arrays.asList(document("same-id", "first", "First", 1, 0,
            "category", "AI")), first).isSuccess());
        assertTrue(store.doStore(Arrays.asList(document("same-id", "second", "Second", 1, 0,
            "category", "Java")), second).isSuccess());

        assertEquals("first", search(store, first).get(0).getContent());
        assertEquals("second", search(store, second).get(0).getContent());
    }

    @Test
    public void shouldApplyQueryWrapperFiltersAndOutputControls() throws Exception {
        QdrantVectorStore store = newStore(true);
        StoreOptions options = StoreOptions.ofCollectionName(collectionName());
        Document matching = document("doc-one", "matching", "Match title", 1, 0,
            "category", "AI", "status", "active", "views", 10, "hidden", "secret");
        Document far = document("doc-two", "far", "Far title", 0, 1,
            "category", "Java", "status", "active", "views", 20, "hidden", "other");
        assertTrue(store.doStore(Arrays.asList(matching, far), options).isSuccess());

        SearchWrapper wrapper = vectorSearch()
            .in("category", Arrays.asList("AI", "Java"))
            .nin("status", Arrays.asList("deleted"))
            .between("views", 5, 15)
            .isNotNull("category")
            .not(group -> group.eq("category", "Java"))
            .outputFields("category", "views")
            .outputVector(true)
            .minScore(0.5);
        List<Document> results = store.doSearch(wrapper, options);

        assertEquals(1, results.size());
        Document result = results.get(0);
        assertEquals("doc-one", result.getId());
        assertEquals("matching", result.getContent());
        assertEquals("Match title", result.getTitle());
        assertArrayEquals(new float[]{1, 0}, result.getVector(), 0.0001f);
        assertEquals("AI", result.getMetadata("category"));
        assertEquals(10, ((Number) result.getMetadata("views")).intValue());
        assertFalse(result.containsMetadata("hidden"));
        assertFalse(result.containsMetadata(QdrantVectorStore.CONTENT_PAYLOAD_KEY));

        SearchWrapper groupedOr = vectorSearch()
            .eq("category", "missing")
            .orCriteria(group -> group.eq("category", "AI").between("views", 5, 15));
        Document groupedResult = store.doSearch(groupedOr, options).get(0);
        assertEquals("doc-one", groupedResult.getId());
        assertNull(groupedResult.getVector());
        assertEquals("doc-one", store.doSearch(vectorSearch().eq("id", "doc-one"), options).get(0).getId());

        try {
            store.doSearch(vectorSearch().gt("views", "invalid"), options);
            fail("An invalid filter must not be ignored");
        } catch (StoreException expected) {
            assertTrue(expected.getMessage().contains("numeric"));
        }
    }

    @Test
    public void shouldUpsertAndDeleteStringAndIntegerIds() throws Exception {
        QdrantVectorStore store = newStore(true);
        StoreOptions options = StoreOptions.ofCollectionName(collectionName());
        UUID uuid = UUID.randomUUID();
        assertTrue(store.doStore(Arrays.asList(
            document("string-id", "before", "Before", 1, 0, "version", 1),
            document(3, "integer", "Integer", 0.8f, 0.2f, "version", 1),
            document(uuid, "uuid", "UUID", 0.6f, 0.4f, "version", 1)), options).isSuccess());

        Document updated = document("string-id", "after", "After", 1, 0, "version", 2);
        assertTrue(store.doUpdate(Arrays.asList(updated), options).isSuccess());
        assertEquals("after", search(store, options).get(0).getContent());
        assertEquals("After", search(store, options).get(0).getTitle());

        assertTrue(store.doDelete(Arrays.asList("string-id", 3, uuid), options).isSuccess());
        assertTrue(search(store, options).isEmpty());
    }

    @Test
    public void shouldRespectDisabledAutoCreation() throws Exception {
        QdrantVectorStore store = newStore(false);
        String missing = collectionName();
        StoreResult result = store.doStore(Arrays.asList(document("id", "content", "title", 1, 0)),
            StoreOptions.ofCollectionName(missing));

        assertFalse(result.isSuccess());
        assertFalse(store.getClient().collectionExistsAsync(missing).get());
    }

    @Test
    public void shouldCheckRealAvailability() {
        QdrantVectorStoreConfig config = new QdrantVectorStoreConfig();
        config.setUri("127.0.0.1:6334");
        assertTrue(config.checkAvailable());
    }

    private QdrantVectorStore newStore(boolean autoCreate) throws Exception {
        QdrantVectorStoreConfig config = new QdrantVectorStoreConfig();
        config.setUri("127.0.0.1:6334");
        config.setDefaultCollectionName("agentsflex_qdrant_default");
        config.setAutoCreateCollection(autoCreate);
        QdrantVectorStore store = new QdrantVectorStore(config);
        stores.add(store);
        return store;
    }

    private String collectionName() {
        String collection = "agentsflex_qdrant_" + UUID.randomUUID().toString().replace("-", "");
        collections.add(collection);
        return collection;
    }

    private List<Document> search(QdrantVectorStore store, StoreOptions options) {
        return store.doSearch(vectorSearch().maxResults(10), options);
    }

    private SearchWrapper vectorSearch() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.setVector(new float[]{1, 0});
        wrapper.setMaxResults(10);
        return wrapper;
    }

    private Document document(Object id, String content, String title, float x, float y,
                              Object... metadata) {
        Document document = new Document(content);
        document.setId(id);
        document.setTitle(title);
        document.setVector(new float[]{x, y});
        for (int i = 0; i < metadata.length; i += 2) {
            document.putMetadata(String.valueOf(metadata[i]), metadata[i + 1]);
        }
        return document;
    }
}
