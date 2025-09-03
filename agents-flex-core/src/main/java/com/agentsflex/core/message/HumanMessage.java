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
package com.agentsflex.core.message;

import com.agentsflex.core.llm.functions.Function;
import com.agentsflex.core.llm.functions.JavaNativeFunctions;

import java.util.*;

public class HumanMessage extends AbstractTextMessage {

    private List<Function> functions;
    private String toolChoice;

    public HumanMessage() {
    }

    public HumanMessage(String content) {
        setContent(content);
    }

    public void addFunction(Function function) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.add(function);
    }

    public void addFunctions(Collection<? extends Function> functions) {
        if (this.functions == null) {
            this.functions = new java.util.ArrayList<>();
        }
        this.functions.addAll(functions);
    }

    public void addFunctions(Class<?> funcClass, String... methodNames) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.addAll(JavaNativeFunctions.from(funcClass, methodNames));
    }

    public void addFunctions(Object funcObject, String... methodNames) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.addAll(JavaNativeFunctions.from(funcObject, methodNames));
    }

    public List<Function> getFunctions() {
        return functions;
    }

    public Map<String, Function> getFunctionMap() {
        if (functions == null) {
            return Collections.emptyMap();
        }
        Map<String, Function> map = new HashMap<>(functions.size());
        for (Function function : functions) {
            map.put(function.getName(), function);
        }
        return map;
    }

    public void setFunctions(List<? extends Function> functions) {
        if (functions == null) {
            this.functions = null;
        } else {
            this.functions = new ArrayList<>(functions);
        }
    }

    public String getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
    }

    @Override
    public String toString() {
        return "HumanMessage{" +
            "functions=" + functions +
            ", toolChoice='" + toolChoice + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
