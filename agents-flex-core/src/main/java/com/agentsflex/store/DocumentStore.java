/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.store;

import com.agentsflex.document.Document;
import com.agentsflex.document.DocumentSplitter;
import com.agentsflex.llm.embedding.EmbeddingModel;

/**
 * 文档存储器
 */
public abstract class DocumentStore extends VectorStore<Document>{

    /**
     * embeddings 模型，可以使用外部的 embeddings 模型，也可以使用自己的 embeddings
     * 许多向量数据库会自带有 embeddings 的能力
     */
    private EmbeddingModel embeddingModel;

    private DocumentSplitter documentSplitter;

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


}
