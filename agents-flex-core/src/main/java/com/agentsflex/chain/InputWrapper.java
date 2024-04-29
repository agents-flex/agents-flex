/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.chain;

import com.agentsflex.agent.Parameter;
import com.agentsflex.chain.node.AgentNode;

import java.util.List;

public class InputWrapper {

    private AgentNode agentNode;
    private List<Parameter> parameters;

    public InputWrapper(AgentNode agentNode, List<Parameter> parameters) {
        this.agentNode = agentNode;
        this.parameters = parameters;
    }

    public AgentNode getAgentNode() {
        return agentNode;
    }

    public void setAgentNode(AgentNode agentNode) {
        this.agentNode = agentNode;
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public void setParameters(List<Parameter> parameters) {
        this.parameters = parameters;
    }
}
