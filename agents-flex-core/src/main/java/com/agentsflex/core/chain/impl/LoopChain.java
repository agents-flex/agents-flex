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
package com.agentsflex.core.chain.impl;

import com.agentsflex.core.chain.ChainEvent;
import com.agentsflex.core.chain.ChainEdge;
import com.agentsflex.core.chain.ChainNode;
import com.agentsflex.core.chain.event.OnNodeFinishedEvent;
import com.agentsflex.core.chain.node.StartNode;

public class LoopChain extends SequentialChain {

    private int maxLoopCount = Integer.MAX_VALUE;

    public LoopChain() {
        this.addNode(new StartNode());
    }

    public int getMaxLoopCount() {
        return maxLoopCount;
    }

    public void setMaxLoopCount(int maxLoopCount) {
        this.maxLoopCount = maxLoopCount;
    }

    public void close() {
        if (this.nodes.size() < 2) {
            return;
        }

        String sourceId = this.nodes.get(this.nodes.size() - 1).getId();
        String targetId = this.nodes.get(1).getId();

        ChainEdge edge = new ChainEdge();
        edge.setSource(sourceId);
        edge.setTarget(targetId);

        super.addEdge(edge);
    }

    @Override
    public void notifyEvent(ChainEvent event) {
        super.notifyEvent(event);
        if (event instanceof OnNodeFinishedEvent){
            ChainNode node = ((OnNodeFinishedEvent) event).getNode();
            Integer exeCount = (Integer) node.getMemory().get(CTX_EXEC_COUNT);
            if (exeCount != null && exeCount > maxLoopCount){
                stopNormal("Loop to the maxLoopCount limit");
            }
        }
    }
}
