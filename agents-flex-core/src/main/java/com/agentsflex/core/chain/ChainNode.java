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

import com.agentsflex.core.memory.ContextMemory;
import com.agentsflex.core.memory.DefaultContextMemory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class ChainNode implements Serializable {

    protected String id;
    protected String name;
    protected String description;

    protected boolean async = false;
    protected boolean awaitAsyncResult = true;
    protected List<ChainEdge> inwardEdges;
    protected List<ChainEdge> outwardEdges;

    protected NodeCondition condition;

    protected ContextMemory memory = new DefaultContextMemory();
    protected ChainNodeStatus nodeStatus = ChainNodeStatus.READY;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public boolean isAwaitAsyncResult() {
        return awaitAsyncResult;
    }

    public void setAwaitAsyncResult(boolean awaitAsyncResult) {
        this.awaitAsyncResult = awaitAsyncResult;
    }

    public List<ChainEdge> getInwardEdges() {
        return inwardEdges;
    }

    public void setInwardEdges(List<ChainEdge> inwardEdges) {
        this.inwardEdges = inwardEdges;
    }

    public List<ChainEdge> getOutwardEdges() {
        return outwardEdges;
    }

    public void setOutwardEdges(List<ChainEdge> outwardEdges) {
        this.outwardEdges = outwardEdges;
    }

    public NodeCondition getCondition() {
        return condition;
    }

    public void setCondition(NodeCondition condition) {
        this.condition = condition;
    }

    public ContextMemory getMemory() {
        return memory;
    }

    public void setMemory(ContextMemory memory) {
        this.memory = memory;
    }

    public ChainNodeStatus getNodeStatus() {
        return nodeStatus;
    }

    public void setNodeStatus(ChainNodeStatus nodeStatus) {
        this.nodeStatus = nodeStatus;
    }

    protected abstract Map<String, Object> execute(Chain chain);

    protected void addOutwardEdge(ChainEdge edge) {
        if (this.outwardEdges == null) {
            this.outwardEdges = new ArrayList<>();
        }
        this.outwardEdges.add(edge);
    }

    protected void addInwardEdge(ChainEdge edge) {
        if (this.inwardEdges == null) {
            this.inwardEdges = new ArrayList<>();
        }
        this.inwardEdges.add(edge);
    }
}
