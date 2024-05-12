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
package com.agentsflex.agent;

import com.agentsflex.chain.Chain;
import com.agentsflex.memory.ContextMemory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 代理（人），有身份 （id），有姓名（name），有记忆 （memory），能执行 execute
 */
public abstract class Agent {
    protected Object id;
    protected String name;
    private ContextMemory memory;
//    private List<String> outputKeys;
//    private Map<String, String> outputMapping;

    public Agent() {
        this.id = UUID.randomUUID().toString();
    }

    public Agent(Object id) {
        this.id = id;
    }

    public Agent(Object id, String name) {
        this.id = id;
        this.name = name;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ContextMemory getMemory() {
        return memory;
    }

    public void setMemory(ContextMemory memory) {
        this.memory = memory;
    }

    public List<Parameter> getInputParameters() {
        List<Parameter> parameters = defineInputParameter();
        return parameters == null ? Collections.emptyList() : parameters;
    }


    public List<String> getOutputKeys() {
        List<String> outputKeys = defineOutputKeys();
        return outputKeys == null ? Collections.emptyList() : outputKeys;
    }
//
//    public void setOutputKeys(List<String> outputKeys) {
//        this.outputKeys = outputKeys;
//    }
//
//    public Map<String, String> getOutputMapping() {
//        return outputMapping;
//    }
//
//    public void setOutputMapping(Map<String, String> outputMapping) {
//        this.outputMapping = outputMapping;
//    }

    public Output execute(Map<String, Object> variables) {
        return execute(variables, null);
    }

    public abstract List<Parameter> defineInputParameter();

    public List<String> defineOutputKeys() {
        return null;
    }

    public abstract Output execute(Map<String, Object> variables, Chain chain);

    @Override
    public String toString() {
        return "Agent{" +
            "id=" + id +
            ", name='" + name + '\'' +
            '}';
    }
}
