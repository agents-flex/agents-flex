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
package com.agentsflex.core.store;

import com.agentsflex.core.llm.embedding.EmbeddingModel;
import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.id.DocumentIdGenerator;
import com.agentsflex.core.document.id.DocumentIdGeneratorFactory;

import java.util.Collection;
import java.util.List;

/**
 * Document Store
 */
public abstract class DocumentStore extends VectorStore<Document> {

    /**
     * DocumentStore can use external embeddings models or its own embeddings
     * Many vector databases come with the ability to embed themselves
     */
    private EmbeddingModel embeddingModel;

    private DocumentSplitter documentSplitter;

    private DocumentIdGenerator documentIdGenerator = DocumentIdGeneratorFactory.getDocumentIdGenerator();

    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public DocumentSplitter getDocumentSplitter() {
        return documentSplitter;
    }

    public void setDocumentSplitter(DocumentSplitter documentSplitter) {
        this.documentSplitter = documentSplitter;
    }

    public DocumentIdGenerator getDocumentIdGenerator() {
        return documentIdGenerator;
    }

    public void setDocumentIdGenerator(DocumentIdGenerator documentIdGenerator) {
        this.documentIdGenerator = documentIdGenerator;
    }

    @Override
    public StoreResult store(List<Document> documents, StoreOptions options) {
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }

        if (documentSplitter != null) {
            documents = documentSplitter.splitAll(documents, documentIdGenerator);
        }
        // use the documentIdGenerator create unique id for document
        else if (documentIdGenerator != null) {
            for (Document document : documents) {
                if (document.getId() == null) {
                    Object id = documentIdGenerator.generateId(document);
                    document.setId(id);
                }
            }
        }

        embedDocumentsIfNecessary(documents, options);

        return storeInternal(documents, options);
    }

    @Override
    public StoreResult delete(Collection<?> ids, StoreOptions options) {
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }
        return deleteInternal(ids, options);
    }

    @Override
    public StoreResult update(List<Document> documents, StoreOptions options) {
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }

        embedDocumentsIfNecessary(documents, options);
        return updateInternal(documents, options);
    }


    @Override
    public List<Document> search(SearchWrapper wrapper, StoreOptions options) {
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }

        if (wrapper.getVector() == null && embeddingModel != null && wrapper.isWithVector()) {
            VectorData vectorData = embeddingModel.embed(Document.of(wrapper.getText()), options.getEmbeddingOptions());
            if (vectorData != null) {
                wrapper.setVector(vectorData.getVector());
            }
        }

        return searchInternal(wrapper, options);
    }


    protected void embedDocumentsIfNecessary(List<Document> documents, StoreOptions options) {
        if (embeddingModel == null) {
            return;
        }
        for (Document document : documents) {
            if (document.getVector() == null) {
                VectorData vectorData = embeddingModel.embed(document, options.getEmbeddingOptions());
                if (vectorData != null) {
                    document.setVector(vectorData.getVector());
                }
            }
        }
    }


    public abstract StoreResult storeInternal(List<Document> documents, StoreOptions options);

    public abstract StoreResult deleteInternal(Collection<?> ids, StoreOptions options);

    public abstract StoreResult updateInternal(List<Document> documents, StoreOptions options);

    public abstract List<Document> searchInternal(SearchWrapper wrapper, StoreOptions options);
}
