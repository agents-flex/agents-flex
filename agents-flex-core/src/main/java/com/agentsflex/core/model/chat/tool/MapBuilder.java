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
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class MapBuilder {
    private String name;
    private String description;
    private final List<Parameter> parameters = new ArrayList<>();
    private Function<Map<String, Object>, Object> invoker;

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

    public MapBuilder function(Function<Map<String, Object>, Object> function) {
        this.invoker = function;
        return this;
    }

    public Tool build() {
        MapFunctionTool tool = new MapFunctionTool();
        tool.setName(name);
        tool.setDescription(description);
        tool.setParameters(parameters.toArray(new Parameter[0]));
        tool.setInvoker(invoker);
        return tool;
    }
}
