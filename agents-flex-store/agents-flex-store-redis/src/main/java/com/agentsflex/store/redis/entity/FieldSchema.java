package com.agentsflex.store.redis.entity;

import lombok.Data;

@Data
public  class FieldSchema {
    private  String name;
    private  FieldType type;
    private  VectorDataType dataType;
    private  Integer dimension;
    private  DistanceMetric metric;

    public FieldSchema(String name, FieldType type, VectorDataType dataType, Integer dimension, DistanceMetric metric) {
        this.name = name;
        this.type = type;
        this.dataType = dataType;
        this.dimension = dimension;
        this.metric = metric;
    }

}
