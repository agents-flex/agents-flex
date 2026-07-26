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
package com.agentsflex.core.store;

import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.id.DocumentIdGenerator;
import com.agentsflex.core.document.id.DocumentIdGeneratorFactory;
import com.agentsflex.core.model.exception.ModelException;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 面向 {@link Document} 的向量存储模板。
 *
 * <p>在调用具体存储实现之前，统一完成文档切分、ID 生成和向量化；查询时可将
 * {@link SearchWrapper#getText()} 自动转换为查询向量。子类只需实现 {@code doXxx}
 * 方法与具体数据库交互。</p>
 */
public abstract class DocumentStore extends VectorStore<Document> {

    /**
     * 外部嵌入模型。为空表示不在本层自动生成向量，适用于已携带向量或由数据库
     * 自行完成嵌入的场景。
     */
    private EmbeddingModel embeddingModel;

    /** 可选的文档切分器；配置后，写入前会先切分全部文档。 */
    private DocumentSplitter documentSplitter;

    /** 文档 ID 生成器，仅为尚未设置 ID 的文档生成 ID。 */
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

    /**
     * 写入文档。处理顺序为：规范化选项、切分或生成 ID、补充向量、调用存储实现。
     */
    @Override
    public StoreResult store(List<Document> documents, StoreOptions options) {
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }

        if (documentSplitter != null) {
            documents = documentSplitter.splitAll(documents, documentIdGenerator);
        }
        // 未启用切分器时，仅为缺少 ID 的原始文档生成 ID。
        else if (documentIdGenerator != null) {
            for (Document document : documents) {
                if (document.getId() == null) {
                    Object id = documentIdGenerator.generateId(document);
                    document.setId(id);
                }
            }
        }

        embedDocumentsIfNecessary(documents, options);

        return doStore(documents, options);
    }

    /** 规范化空选项后调用具体存储的删除实现。 */
    @Override
    public StoreResult delete(Collection<?> ids, StoreOptions options) {
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }
        return doDelete(ids, options);
    }

    /** 更新前为尚未携带向量的文档补充向量。 */
    @Override
    public StoreResult update(List<Document> documents, StoreOptions options) {
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }

        embedDocumentsIfNecessary(documents, options);
        return doUpdate(documents, options);
    }


    /**
     * 执行文档检索。查询向量为空、已配置嵌入模型且允许向量检索时，
     * 会先将查询文本转换为向量。
     */
    @Override
    public List<Document> search(SearchWrapper wrapper, StoreOptions options) {
        Objects.requireNonNull(wrapper, "wrapper must not be null");
        if (options == null) {
            options = StoreOptions.DEFAULT;
        }

        SearchWrapper executionWrapper = wrapper.copy().validate();
        if (executionWrapper.getVector() == null && embeddingModel != null && executionWrapper.isWithVector()) {
            VectorData vectorData = embeddingModel.embed(
                Document.of(executionWrapper.getText()), options.getEmbeddingOptions());
            if (vectorData == null) {
                throw new ModelException("Embedding model does not contain vector data");
            }
            executionWrapper.setVector(vectorData.getVector());
        }

        return doSearch(executionWrapper, options);
    }


    /**
     * 为未携带向量的文档生成向量。已有向量会被保留，嵌入模型为空时直接跳过。
     */
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


    /** 将完成预处理的文档写入具体存储。 */
    protected abstract StoreResult doStore(List<Document> documents, StoreOptions options);

    /** 从具体存储中删除指定 ID。 */
    protected abstract StoreResult doDelete(Collection<?> ids, StoreOptions options);

    /** 将完成向量补充的文档更新到具体存储。 */
    protected abstract StoreResult doUpdate(List<Document> documents, StoreOptions options);

    /** 在具体存储中执行检索。 */
    protected abstract List<Document> doSearch(SearchWrapper wrapper, StoreOptions options);
}
