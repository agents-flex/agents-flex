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
package com.agentsflex.core.chain.node;

import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.agent.Output;
import com.agentsflex.core.agent.OutputKey;
import com.agentsflex.core.agent.Parameter;
import com.agentsflex.core.chain.Chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentNode extends AbstractBaseNode {
    private Agent agent;
    private Map<String, String> outputMapping;

    public AgentNode() {
    }

    public AgentNode(Agent agent) {
        this.agent = agent;
    }

    @Override
    public Object getId() {
        return this.id != null ? this.id : agent.getId();
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Map<String, String> getOutputMapping() {
        return outputMapping;
    }

    public void setOutputMapping(Map<String, String> outputMapping) {
        this.outputMapping = outputMapping;
    }

    @Override
    public Map<String, Object> execute(Chain chain) {

        Map<String, Object> variables = new HashMap<>();
        List<Parameter> requiredParameters = null;

        List<Parameter> inputParameters = agent.getInputParameters();

        // Agent 未定义输入参数
        if (inputParameters.isEmpty()) {
            variables.putAll(chain.getMemory().getAll());
        }
        // Agent 定义了固定的输入参数
        else {
            for (Parameter parameter : inputParameters) {
                Object value = chain.get(parameter.getName());

                //当只有一个参数时，或者当前参数为默认参数时，尝试使用 default 数据库
                if (value == null && (parameter.isDefault() || inputParameters.size() == 1)) {
                    value = chain.get(Output.DEFAULT_VALUE_KEY);
                }
                if (value == null && parameter.isRequired()) {
                    if (requiredParameters == null) {
                        requiredParameters = new ArrayList<>();
                    }
                    requiredParameters.add(parameter);
                } else {
                    variables.put(parameter.getName(), value);
                }
            }
        }

        if (requiredParameters != null) {
            chain.waitInput(requiredParameters, this);
            return null;
        }

        Output output = agent.execute(variables, chain);
        List<OutputKey> outputKeys = agent.getOutputKeys();
        if (outputKeys == null || outputKeys.isEmpty()
            || outputMapping == null || outputMapping.isEmpty()) {
            return output;
        }

        Map<String, Object> newResult = new HashMap<>(outputKeys.size());
        for (OutputKey outputKey : outputKeys) {
            String oldKey = outputKey.getKey();
            String newKey = outputMapping.getOrDefault(oldKey, oldKey);
            newResult.put(newKey, output.get(oldKey));
        }

        return newResult;
    }

    @Override
    public String toString() {
        return "AgentNode{" +
            "agent=" + agent +
            ", id=" + id +
            ", skip=" + skip +
            '}';
    }
}
