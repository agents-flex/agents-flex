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

import java.util.ArrayList;
import java.util.List;

public class InputParameter {
    private String name;
    private String description;
    private DataType dataType;
    private String ref;
    private RefType refType;
    private boolean required;

    private List<InputParameter> children;

    public InputParameter() {
    }

    public InputParameter(String name) {
        this.name = name;
    }

    public InputParameter(String name, DataType dataType) {
        this.name = name;
        this.dataType = dataType;
    }

    public InputParameter(String name, boolean required) {
        this.name = name;
        this.required = required;
    }

    public InputParameter(String name, DataType dataType, boolean required) {
        this.name = name;
        this.dataType = dataType;
        this.required = required;
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

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public List<InputParameter> getChildren() {
        return children;
    }

    public void setChildren(List<InputParameter> children) {
        this.children = children;
    }

    public void addChild(InputParameter inputParameter) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(inputParameter);
    }

    @Override
    public String toString() {
        return "InputParameter{" +
            "name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", dataType=" + dataType +
            ", ref='" + ref + '\'' +
            ", refType=" + refType +
            ", required=" + required +
            ", children=" + children +
            '}';
    }
}
