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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class NodeContext {

    private final ChainNode node;
    private final Chain chain;
    public ChainNode currentNode;
    public ChainNode prevNode;
    public String fromEdgeId;

    private AtomicInteger triggerCount = new AtomicInteger(0);
    private List<String> triggerEdgeIds = new ArrayList<>();

    private AtomicInteger executeCount = new AtomicInteger(0);
    private List<String> executeEdgeIds = new ArrayList<>();

    public NodeContext(ChainNode node, Chain chain) {
        this.node = node;
        this.chain = chain;
    }

    public ChainNode getNode() {
        return node;
    }

    public Chain getChain() {
        return chain;
    }

    public ChainNode getCurrentNode() {
        return currentNode;
    }

    public ChainNode getPrevNode() {
        return prevNode;
    }

    public String getFromEdgeId() {
        return fromEdgeId;
    }

    public int getTriggerCount() {
        return triggerCount.get();
    }

    public List<String> getTriggerEdgeIds() {
        return triggerEdgeIds;
    }

    public int getExecuteCount() {
        return executeCount.get();
    }

    public List<String> getExecuteEdgeIds() {
        return executeEdgeIds;
    }

    public boolean isUpstreamFullyExecuted() {
        List<ChainEdge> inwardEdges = currentNode.getInwardEdges();
        if (inwardEdges == null || inwardEdges.isEmpty()) {
            return true;
        }

        List<String> shouldBeTriggerIds = inwardEdges.stream().map(ChainEdge::getId).collect(Collectors.toList());
        return triggerEdgeIds.size() >= shouldBeTriggerIds.size()
            && shouldBeTriggerIds.parallelStream().allMatch(triggerEdgeIds::contains);
    }

    public void recordTrigger(Chain.ExecuteNode executeNode) {
        this.currentNode = executeNode.currentNode;
        this.prevNode = executeNode.prevNode;
        this.fromEdgeId = executeNode.fromEdgeId;

        triggerCount.incrementAndGet();
        triggerEdgeIds.add(executeNode.fromEdgeId);
    }

    public synchronized void recordExecute(Chain.ExecuteNode executeNode) {
        executeCount.incrementAndGet();
        executeEdgeIds.add(executeNode.fromEdgeId);
    }
}
