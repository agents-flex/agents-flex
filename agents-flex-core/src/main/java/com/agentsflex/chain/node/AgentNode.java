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
package com.agentsflex.chain.node;

import com.agentsflex.agent.Agent;
import com.agentsflex.chain.Chain;
import com.agentsflex.chain.NodeResult;
import com.agentsflex.chain.result.SingleNodeResult;

public class AgentNode extends BaseNode {
    private final Agent<?> agent;

    public AgentNode(Agent<?> agent) {
        this.agent = agent;
    }

    @Override
    public Object getId() {
        return this.id != null ? this.id : agent.getId();
    }

    @Override
    public NodeResult<?> execute(NodeResult<?> prevResult, Chain<?, ?> chain) {
        Object result = agent.execute(prevResult.getValue(), chain);
        return new SingleNodeResult(result);
    }
}
