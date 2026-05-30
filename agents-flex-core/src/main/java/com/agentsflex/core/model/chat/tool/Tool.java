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

import java.util.Map;
import java.util.function.Function;

public interface Tool {

    String getName();

    String getDescription();

    Parameter[] getParameters();

    Object invoke(Map<String, Object> argsMap);

    static MapBuilder builder() {
        return new MapBuilder();
    }

    static MapBuilder builder(String name) {
        return new MapBuilder().name(name);
    }

    static MapBuilder builder(String name, String description) {
        return new MapBuilder().name(name).description(description);
    }

    static <I> TypedBuilder<I> builder(String name, Function<I, ?> function) {
        return new TypedBuilder<I>().name(name).function(function);
    }

    static <I> TypedBuilder<I> builder(String name, Class<I> inputType) {
        return new TypedBuilder<I>().name(name).inputType(inputType);
    }

    static <I> TypedBuilder<I> builder(String name, Class<I> inputType, Function<I, ?> function) {
        return new TypedBuilder<I>().name(name).inputType(inputType).function(function);
    }

}
