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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class Parameter implements Serializable, Cloneable {
    protected String id;
    protected String name;
    protected String description;
    protected DataType dataType = DataType.String;

    /**
     * 数据类型：文字内容、图片、音频、视频、文件
     */
    protected String contentType;
    protected String ref;
    protected RefType refType;
    protected String value;
    protected boolean required;
    protected String defaultValue;
    protected List<Parameter> children;

    /**
     * 枚举值列表
     */
    protected List<Object> enums;

    /**
     * 用户输入的表单类型，例如："input"  "textarea"  "select"  "radio"  "checkbox"  等等
     */
    protected String formType;

    /**
     * 用户界面上显示的提示文字，用于引导用户进行选择
     */
    protected String formLabel;

    /**
     * 用户界面上显示的描述文字，用于引导用户进行选择
     */
    protected String formDescription;

    /**
     * 表单的其他属性
     */
    protected String formAttrs;


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

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
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

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
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

    public List<Object> getEnums() {
        return enums;
    }

    public void setEnums(List<Object> enums) {
        this.enums = enums;
    }

    public void setEnumsObject(Object enumsObject) {
        if (enumsObject == null) {
            this.enums = null;
        } else if (enumsObject instanceof Collection) {
            this.enums = new ArrayList<>();
            this.enums.addAll((Collection<?>) enumsObject);
        } else if (enumsObject.getClass().isArray()) {
            this.enums = new ArrayList<>();
            this.enums.addAll(Arrays.asList((Object[]) enumsObject));
        } else {
            this.enums = new ArrayList<>(1);
            this.enums.add(enumsObject);
        }
    }

    public String getFormType() {
        return formType;
    }

    public void setFormType(String formType) {
        this.formType = formType;
    }

    public String getFormLabel() {
        return formLabel;
    }

    public void setFormLabel(String formLabel) {
        this.formLabel = formLabel;
    }

    public String getFormDescription() {
        return formDescription;
    }

    public void setFormDescription(String formDescription) {
        this.formDescription = formDescription;
    }

    public String getFormAttrs() {
        return formAttrs;
    }

    public void setFormAttrs(String formAttrs) {
        this.formAttrs = formAttrs;
    }

    @Override
    public String toString() {
        return "Parameter{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", dataType=" + dataType +
            ", contentType='" + contentType + '\'' +
            ", ref='" + ref + '\'' +
            ", refType=" + refType +
            ", value='" + value + '\'' +
            ", required=" + required +
            ", defaultValue='" + defaultValue + '\'' +
            ", children=" + children +
            ", enums=" + enums +
            ", formType='" + formType + '\'' +
            ", formLabel='" + formLabel + '\'' +
            ", formDescription='" + formDescription + '\'' +
            ", formAttrs='" + formAttrs + '\'' +
            '}';
    }

    @Override
    public Parameter clone() {
        try {
            Parameter clone = (Parameter) super.clone();
            if (this.children != null) {
                clone.children = new ArrayList<>(this.children.size());
                for (Parameter child : this.children) {
                    clone.children.add(child.clone()); // 递归克隆
                }
            }
            if (this.enums != null) {
                clone.enums = new ArrayList<>(this.enums.size());
                clone.enums.addAll(this.enums);
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
