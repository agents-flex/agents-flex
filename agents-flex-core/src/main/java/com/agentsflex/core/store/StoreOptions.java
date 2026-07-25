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

import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.util.Metadata;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次存储操作的通用选项。
 *
 * <p>集合、索引、分区等字段由具体存储按需解释；存储实现也可以继承本类扩展
 * 专属选项。该对象还继承了 {@link Metadata}，可携带额外参数。</p>
 */
public class StoreOptions extends Metadata {

    /**
     * 共享默认选项。该实例禁止修改核心字段，调用方需要定制时应创建新实例。
     */
    public static final StoreOptions DEFAULT = new StoreOptions() {
        @Override
        public void setCollectionName(String collectionName) {
            throw new IllegalStateException("Can not set collectionName to the default instance.");
        }

        @Override
        public void setPartitionNames(List<String> partitionNames) {
            throw new IllegalStateException("Can not set partitionName to the default instance.");
        }

        @Override
        public void setEmbeddingOptions(EmbeddingOptions embeddingOptions) {
            throw new IllegalStateException("Can not set embeddingOptions to the default instance.");
        }
    };

    /**
     * 本次操作使用的集合名称。
     */
    private String collectionName;

    /**
     * 本次操作使用的索引名称。
     */
    private String indexName;

    /**
     * 本次操作涉及的分区名称列表。
     */
    private List<String> partitionNames;

    /**
     * 文本向量化时传递给嵌入模型的选项。
     */
    private EmbeddingOptions embeddingOptions = EmbeddingOptions.DEFAULT;


    public String getCollectionName() {
        return collectionName;
    }

    /** 返回集合名；未配置时返回调用方提供的默认值。 */
    public String getCollectionNameOrDefault(String other) {
        return StringUtil.hasText(collectionName) ? collectionName : other;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public List<String> getPartitionNames() {
        return partitionNames;
    }

    /** 返回第一个分区名；没有分区时返回 {@code null}。 */
    public String getPartitionName() {
        return partitionNames != null && !partitionNames.isEmpty() ? partitionNames.get(0) : null;
    }

    /** 返回分区列表；未配置时返回不可变空列表。 */
    public List<String> getPartitionNamesOrEmpty() {
        return partitionNames == null ? Collections.emptyList() : partitionNames;
    }

    public void setPartitionNames(List<String> partitionNames) {
        this.partitionNames = partitionNames;
    }

    /**
     * 追加一个分区名。
     *
     * @return 当前选项对象
     */
    public StoreOptions partitionName(String partitionName) {
        if (this.partitionNames == null) {
            this.partitionNames = new ArrayList<>(1);
        }
        this.partitionNames.add(partitionName);
        return this;
    }


    public EmbeddingOptions getEmbeddingOptions() {
        return embeddingOptions;
    }

    public void setEmbeddingOptions(EmbeddingOptions embeddingOptions) {
        this.embeddingOptions = embeddingOptions;
    }


    /** 创建仅指定集合名的存储选项。 */
    public static StoreOptions ofCollectionName(String collectionName) {
        StoreOptions storeOptions = new StoreOptions();
        storeOptions.setCollectionName(collectionName);
        return storeOptions;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    /** 返回索引名；未配置时返回调用方提供的默认值。 */
    public String getIndexNameOrDefault(String other) {
        return StringUtil.hasText(indexName) ? indexName : other;
    }
}
