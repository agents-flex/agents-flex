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
import com.agentsflex.chain.event.OnErrorEvent;
import com.agentsflex.chain.event.OnInvokeAfter;
import com.agentsflex.chain.node.AgentNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 顺序执行
 *
 * @param <Input>
 * @param <Output>
 */
public class SequentialChain<Input, Output> extends BaseChain<Input, Output> {

    public SequentialChain() {
    }

    public SequentialChain(Agent<?>... agents) {
        List<ChainNode> chainNodes = new ArrayList<>(agents.length);
        for (Agent<?> agent : agents) {
            chainNodes.add(new AgentNode(agent));
        }
        setInvokers(chainNodes);
    }

    public SequentialChain(ChainNode... chainNodes) {
        setInvokers(new ArrayList<>(Arrays.asList(chainNodes)));
    }


    public SequentialChain(Collection<ChainNode> chainNodes) {
        setInvokers(new ArrayList<>(chainNodes));
    }


    @Override
    protected void doExecuteAndSetOutput() {
        for (ChainNode node : chainNodes) {
            if (isStop()) {
                break;
            }
            try {
                Object result = node.execute(this.lastResult, this);
                if (!node.isSkip()) {
                    this.lastResult = result;
                }
                notify(new OnInvokeAfter(this, node, lastResult));
            } catch (Exception e) {
                notify(new OnErrorEvent(this, e));
            }
        }

        //agent call stopAndOutput()...
        if (isStop() && this.output != null) {
            return;
        }

        //noinspection unchecked
        this.output = (Output) this.lastResult;
    }

}
