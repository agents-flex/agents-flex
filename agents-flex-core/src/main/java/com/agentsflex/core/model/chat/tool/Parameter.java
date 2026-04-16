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
package com.agentsflex.core.model.chat.tool;

import com.agentsflex.core.util.JsonSchemaTypeMapper;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具方法参数定义
 *
 * @author fuhai
 * @since 2023/10/01
 */
public class Parameter implements Serializable {

    private static final long serialVersionUID = 1L;

    protected String name;
    protected String type = "string";
    protected String description;
    protected String[] enums;
    protected boolean required = false;
    protected Object defaultValue;
    protected List<Parameter> children;
    protected Parameter itemsParameter; // 当 type = array 类型的属性定义，其 items 属性定义

    // =============== Getters and Setters ===============

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Nullable
    public String[] getEnums() {
        return enums != null ? enums.clone() : null;
    }

    public void setEnums(@Nullable String[] enums) {
        this.enums = enums != null ? enums.clone() : null;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    @Nullable
    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(@Nullable Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Nullable
    public List<Parameter> getChildren() {
        return children != null ? new ArrayList<>(children) : null;
    }

    public void setChildren(@Nullable List<Parameter> children) {
        this.children = children != null ? new ArrayList<>(children) : null;
    }

    public void addChild(Parameter parameter) {
        if (children == null) {
            children = new ArrayList<>();
        }
        children.add(parameter);
    }

    public Parameter getItemsParameter() {
        return itemsParameter;
    }

    public void setItemsParameter(Parameter itemsParameter) {
        this.itemsParameter = itemsParameter;
    }

    public boolean isArrayType() {
        return "array".equals(type);
    }

    public boolean isObjectType() {
        return "object".equals(type);
    }


    // =============== Builder ===============

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String type = "string";
        private String description;
        private String[] enums;
        private boolean required = false;
        private Object defaultValue;
        private List<Parameter> children;
        private Parameter itemsParameter;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder typeOfClass(Class<?> type) {
            this.type = JsonSchemaTypeMapper.mapToSchemaType(type);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder enums(String... enums) {
            this.enums = enums != null ? enums.clone() : null;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder addChild(Parameter child) {
            if (this.children == null) {
                this.children = new ArrayList<>();
            }
            this.children.add(child);
            return this;
        }

        public Builder children(List<Parameter> children) {
            this.children = children != null ? new ArrayList<>(children) : null;
            return this;
        }

        public Builder itemsParameter(Parameter itemsParameter) {
            this.itemsParameter = itemsParameter;
            return this;
        }


        public Parameter build() {
            Parameter param = new Parameter();
            param.setName(name);
            param.setType(type);
            param.setDescription(description);
            param.setEnums(enums);
            param.setRequired(required);
            param.setDefaultValue(defaultValue);
            param.setChildren(children);
            param.setItemsParameter(itemsParameter);
            return param;
        }
    }
}
