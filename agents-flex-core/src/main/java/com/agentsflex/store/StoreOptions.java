package com.agentsflex.store;

import com.agentsflex.util.StringUtil;

public class StoreOptions {

    public static  StoreOptions EMPTY = new StoreOptions(){
        @Override
        public void setCollectionName(String collectionName) {
            throw new IllegalStateException("Can not set collectionName to the empty instance.");
        }

        @Override
        public void setPartitionName(String partitionName) {
            throw new IllegalStateException("Can not set partitionName to the empty instance.");
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
}
