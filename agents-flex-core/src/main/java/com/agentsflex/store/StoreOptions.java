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
package com.agentsflex.store;

import com.agentsflex.llm.embedding.EmbeddingOptions;
import com.agentsflex.util.StringUtil;

/**
 * 每个 store 都可以有自己的实现类
 */
public class StoreOptions {

    public static final StoreOptions DEFAULT = new StoreOptions() {
        @Override
        public void setCollectionName(String collectionName) {
            throw new IllegalStateException("Can not set collectionName to the default instance.");
        }

        @Override
        public void setPartitionName(String partitionName) {
            throw new IllegalStateException("Can not set partitionName to the default instance.");
        }
    };

    /**
     * 集合名称
     */
    private String collectionName;

    /**
     * 分区名称
     */
    private String partitionName;

    /**
     * embedding 的配置内容
     */
    private EmbeddingOptions embeddingOptions;


    public String getCollectionName() {
        return collectionName;
    }

    public String getCollectionName(String orDefault) {
        return StringUtil.hasText(collectionName) ? collectionName : orDefault;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public String getPartitionName() {
        return partitionName;
    }

    public String getPartitionName(String orDefault) {
        return StringUtil.hasText(partitionName) ? partitionName : orDefault;
    }

    public void setPartitionName(String partitionName) {
        this.partitionName = partitionName;
    }

    public EmbeddingOptions getEmbeddingOptions() {
        return embeddingOptions;
    }

    public void setEmbeddingOptions(EmbeddingOptions embeddingOptions) {
        this.embeddingOptions = embeddingOptions;
    }


    public static StoreOptions ofCollectionName(String collectionName) {
        StoreOptions storeOptions = new StoreOptions();
        storeOptions.setCollectionName(collectionName);
        return storeOptions;
    }
}
