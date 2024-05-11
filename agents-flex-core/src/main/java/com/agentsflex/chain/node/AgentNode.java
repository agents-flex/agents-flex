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
package com.agentsflex.chain.node;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.Output;
import com.agentsflex.agent.Parameter;
import com.agentsflex.chain.Chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentNode extends AbstractBaseNode {
    private Agent agent;

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

    @Override
    public Map<String, Object> execute(Chain chain) {
        List<Parameter> inputParameters = agent.getInputParameters();
        Map<String, Object> variables = new HashMap<>();
        List<Parameter> requiredParameters = null;
        for (Parameter parameter : inputParameters) {
            Object value = chain.get(parameter.getName());
            if (value == null && parameter.isRequired()) {
                if (requiredParameters == null) {
                    requiredParameters = new ArrayList<>();
                }
                requiredParameters.add(parameter);

            } else {
                variables.put(parameter.getName(), value);
            }
        }

        if (requiredParameters != null) {
            chain.waitInput(requiredParameters, this);
            return null;
        }

        Output output = agent.execute(variables);
        List<String> outputKeys = agent.getOutputKeys();
        if (outputKeys != null && !outputKeys.isEmpty()) {
            Map<String, String> outputMapping = agent.getOutputMapping();

            Map<String, Object> result = new HashMap<>();
            for (String outputKey : outputKeys) {
                String resultKey = outputMapping.getOrDefault(outputKey, outputKey);
                result.put(resultKey, output.get(outputKey));
            }
            return result;
        } else {
            return output;
        }
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
