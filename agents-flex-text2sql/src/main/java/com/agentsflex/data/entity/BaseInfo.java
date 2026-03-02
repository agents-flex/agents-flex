package com.agentsflex.data.entity;

import java.io.Serializable;

public class BaseInfo implements Serializable {

    protected String name;
    protected String description;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String genName() {
        return name;
    }

    public String genDescription() {
        return description;
    }
}
