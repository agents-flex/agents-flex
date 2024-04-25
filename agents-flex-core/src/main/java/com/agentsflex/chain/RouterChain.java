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
import com.agentsflex.chain.events.OnInvokeAfter;
import com.agentsflex.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public abstract class RouterChain<Input, Output> extends BaseChain<Input, Output> {

    public RouterChain() {
    }

    public RouterChain(Agent<?>... agents) {
        List<Invoker> invokers = new ArrayList<>(agents.length);
        for (Agent<?> agent : agents) {
            invokers.add(new AgentInvoker(agent));
        }
        setInvokers(invokers);
    }

    public RouterChain(Invoker... invokers) {
        setInvokers(new ArrayList<>(Arrays.asList(invokers)));
    }


    @Override
    protected void doExecuteAndSetOutput() {
        String result = route();
        if (StringUtil.noText(result)) {
            stop();
            return;
        }

        List<Invoker> findInvokers = new ArrayList<>();
        String[] ids = result.split(",");
        for (String id : ids) {
            for (Invoker invoker : this.invokers) {
                if (Objects.equals(id, String.valueOf(invoker.getId()))) {
                    findInvokers.add(invoker);
                }
            }
        }

        if (findInvokers.isEmpty()) {
            stop();
            return;
        }

        if (findInvokers.size() == 1) {
            Invoker invoker = findInvokers.get(0);
            this.lastResult = invoker.invoke(this.lastResult, this);
            notify(new OnInvokeAfter(this, invoker, this.lastResult));
        } else {
            lastResult = new SequentialChain<>(findInvokers).execute(this.lastResult);
        }
    }

    protected abstract String route();

}
