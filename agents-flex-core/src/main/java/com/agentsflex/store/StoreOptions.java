package com.agentsflex.store;

import com.agentsflex.llm.embedding.EmbeddingOptions;
import com.agentsflex.util.StringUtil;

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
