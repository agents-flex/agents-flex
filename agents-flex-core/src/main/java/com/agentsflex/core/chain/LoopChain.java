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
package com.agentsflex.core.chain;

import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.chain.event.OnNodeFinishedEvent;
import com.agentsflex.core.chain.event.OnNodeStartEvent;
import com.agentsflex.core.chain.node.AgentNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 循环执行连
 */
public class LoopChain extends BaseChain {

    private long intervalMillis = 0L;
    private int currentIndex = 0;


    public LoopChain() {
    }

    public LoopChain(Agent... agents) {
        List<ChainNode> chainNodes = new ArrayList<>(agents.length);
        for (Agent agent : agents) {
            chainNodes.add(new AgentNode(agent));
        }
        setNodes(chainNodes);
    }

    public LoopChain(ChainNode... chainNodes) {
        setNodes(new ArrayList<>(Arrays.asList(chainNodes)));
    }


    @Override
    protected void executeInternal() {
        while (getStatus() == ChainStatus.START) {
            List<ChainNode> nodes = getNodes();
            for (int i = currentIndex; i < nodes.size(); i++) {
                try {
                    ChainNode node = nodes.get(i);
                    Map<String, Object> result = null;
                    try {
                        notifyEvent(new OnNodeStartEvent(node));
                        if (this.getStatus() != ChainStatus.START) {
                            break;
                        }
                        result = node.execute(this);
                    } finally {
                        notifyEvent(new OnNodeFinishedEvent(node, result));
                    }

                    if (this.getStatus() != ChainStatus.START) {
                        break;
                    }

                    if (node.isSkip()) {
                        continue;
                    }

                    if (result != null) {
                        this.getMemory().putAll(result);
                    }
                } finally {
                    this.currentIndex = i;
                }
            }

            if (getStatus() != ChainStatus.START) {
                break;
            }
            if (this.intervalMillis > 0) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    protected void resumeInternal(Map<String, Object> variables) {
        if (variables != null) {
            this.getMemory().putAll(variables);
        }
        executeInternal();
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }


}
