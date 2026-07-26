/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.store;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

public class DocumentStoreSearchTest {

    @Test
    public void shouldEmbedAnExecutionCopyWithoutMutatingOriginalRequest() {
        CapturingStore store = new CapturingStore();
        store.setEmbeddingModel(new EmbeddingModel() {
            @Override
            public VectorData embed(Document document, EmbeddingOptions options) {
                VectorData result = new VectorData();
                result.setVector(new float[]{0.25f, 0.75f});
                return result;
            }
        });
        SearchWrapper original = new SearchWrapper().text("query").eq("tenant", "a");

        store.search(original);

        assertNull(original.getVector());
        assertNotSame(original, store.executedRequest);
        assertArrayEquals(new float[]{0.25f, 0.75f}, store.executedRequest.getVector(), 0f);
    }

    private static class CapturingStore extends DocumentStore {
        private SearchWrapper executedRequest;

        @Override
        protected StoreResult doStore(List<Document> documents, StoreOptions options) {
            return StoreResult.success();
        }

        @Override
        protected StoreResult doDelete(Collection<?> ids, StoreOptions options) {
            return StoreResult.success();
        }

        @Override
        protected StoreResult doUpdate(List<Document> documents, StoreOptions options) {
            return StoreResult.success();
        }

        @Override
        protected List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
            this.executedRequest = wrapper;
            return Collections.emptyList();
        }
    }
}
