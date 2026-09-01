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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class MapBuilder {
    private String name;
    private String description;
    private final List<Parameter> parameters = new ArrayList<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private Function<Map<String, Object>, Object> invoker;
    private ToolExecutionTarget executionTarget = ToolExecutionTarget.LOCAL;

    public MapBuilder name(String name) {
        this.name = name;
        return this;
    }

    public MapBuilder description(String description) {
        this.description = description;
        return this;
    }

    public MapBuilder addParameter(Parameter parameter) {
        this.parameters.add(parameter);
        return this;
    }

    /** 添加或覆盖一项工具元数据。 */
    public MapBuilder metadata(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("metadata key must not be null");
        }
        this.metadata.put(key, value);
        return this;
    }

    /** 批量添加工具元数据。 */
    public MapBuilder metadata(Map<String, ?> values) {
        if (values != null) {
            this.metadata.putAll(values);
        }
        return this;
    }

    public MapBuilder function(Function<Map<String, Object>, Object> function) {
        this.invoker = function;
        return this;
    }

    /** 设置 ToolCall 的实际执行位置。 */
    public MapBuilder executionTarget(ToolExecutionTarget value) {
        if (value == null) {
            throw new IllegalArgumentException("executionTarget must not be null");
        }
        this.executionTarget = value;
        return this;
    }

    public Tool build() {
        MapFunctionTool tool = new MapFunctionTool();
        tool.setName(name);
        tool.setDescription(description);
        tool.setParameters(parameters.toArray(new Parameter[0]));
        tool.setMetadata(metadata);
        tool.setExecutionTarget(executionTarget);
        tool.setInvoker(invoker);
        return tool;
    }
}
