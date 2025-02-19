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
package com.agentsflex.core.chain;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public class Parameter implements Serializable {
    protected String id;
    protected String name;
    protected String description;
    protected DataType dataType;
    protected String ref;
    protected RefType refType;
    protected String value;
    protected boolean required;
    protected List<Parameter> children;

    public Parameter() {
    }

    public Parameter(String name) {
        this.name = name;
    }

    public Parameter(String name, DataType dataType) {
        this.name = name;
        this.dataType = dataType;
    }

    public Parameter(String name, boolean required) {
        this.name = name;
        this.required = required;
    }

    public Parameter(String name, DataType dataType, boolean required) {
        this.name = name;
        this.dataType = dataType;
        this.required = required;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

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

    public DataType getDataType() {
        return dataType;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public RefType getRefType() {
        return refType;
    }

    public void setRefType(RefType refType) {
        this.refType = refType;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public List<Parameter> getChildren() {
        return children;
    }

    public void setChildren(List<Parameter> children) {
        this.children = children;
    }

    public void addChild(Parameter parameter) {
        if (children == null) {
            children = new java.util.ArrayList<>();
        }
        children.add(parameter);
    }

    public void addChildren(Collection<Parameter> parameters) {
        if (children == null) {
            children = new java.util.ArrayList<>();
        }
        children.addAll(parameters);
    }

    @Override
    public String toString() {
        return "Parameter{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", dataType=" + dataType +
            ", ref='" + ref + '\'' +
            ", refType=" + refType +
            ", value='" + value + '\'' +
            ", required=" + required +
            ", children=" + children +
            '}';
    }
}
