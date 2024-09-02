package com.agentsflex.store.redis.entity;


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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FieldType getType() {
        return type;
    }

    public void setType(FieldType type) {
        this.type = type;
    }

    public VectorDataType getDataType() {
        return dataType;
    }

    public void setDataType(VectorDataType dataType) {
        this.dataType = dataType;
    }

    public Integer getDimension() {
        return dimension;
    }

    public void setDimension(Integer dimension) {
        this.dimension = dimension;
    }

    public DistanceMetric getMetric() {
        return metric;
    }

    public void setMetric(DistanceMetric metric) {
        this.metric = metric;
    }
}
