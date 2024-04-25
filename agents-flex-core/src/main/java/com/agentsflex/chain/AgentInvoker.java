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

import com.agentsflex.agent.Agent;

public class AgentInvoker implements Invoker {
    private final Agent<?> agent;
    private Condition condition;
    public AgentInvoker(Agent<?> agent) {
        this.agent = agent;
    }

    public AgentInvoker(Agent<?> agent, Condition condition) {
        this.agent = agent;
        this.condition = condition;
    }

    @Override
    public boolean checkCondition(Object prevResult, Chain<?, ?> chain) {
        return condition == null || condition.check(prevResult, chain);
    }

    @Override
    public Object invoke(Object prevResult, Chain<?, ?> chain) {
        return agent.execute(prevResult, chain);
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
    }

    @Override
    public Object getId() {
        return agent.getId();
    }
}
