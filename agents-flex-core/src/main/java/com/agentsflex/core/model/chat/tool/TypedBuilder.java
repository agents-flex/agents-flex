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


import java.util.function.Function;

public class TypedBuilder<I> {

    private Class<I> inputType;
    private String name;
    private String description;
    private Function<I,?> function;


    public TypedBuilder<I> name(String name) {
        this.name = name;
        return this;
    }

    public TypedBuilder<I> description(String description) {
        this.description = description;
        return this;
    }

    public TypedBuilder<I> inputType(Class<I> inputType) {
        this.inputType = inputType;
        return this;
    }

    public TypedBuilder<I> function(Function<I,?> function) {
        this.function = function;
        return this;
    }

    public Tool build() {
        TypedFunctionTool<I> tool = new TypedFunctionTool<>();

        tool.setName(name);
        tool.setDescription(description);
        tool.setInputType(inputType);
        tool.setFunction(function);

        tool.setParameters(
            ToolParameterResolver.resolve(
                inputType
            )
        );

        return tool;
    }
}
