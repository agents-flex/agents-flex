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
    protected List<ChainEdge> inwardEdges;
    protected List<ChainEdge> outwardEdges;

    protected NodeCondition condition;

    protected ContextMemory memory = new DefaultContextMemory();
    protected ChainNodeStatus nodeStatus = ChainNodeStatus.READY;


    // 循环执行相关属性
    protected boolean loopEnable = false;           // 是否启用循环执行
    protected long loopIntervalMs = 1000;            // 循环间隔时间（毫秒）
    protected NodeCondition loopBreakCondition;      // 跳出循环的条件
    protected int maxLoopCount = 0;                  // 0 表示不限制循环次数

    // 算力消耗定义，积分消耗
    protected long computeCost = 0;

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

    public void setNodeStatusFinished() {
        if (this.nodeStatus == ChainNodeStatus.ERROR) {
            this.setNodeStatus(ChainNodeStatus.FINISHED_ABNORMAL);
        } else {
            this.setNodeStatus(ChainNodeStatus.FINISHED_NORMAL);
        }
    }

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

    public boolean isLoopEnable() {
        return loopEnable;
    }

    public void setLoopEnable(boolean loopEnable) {
        this.loopEnable = loopEnable;
    }

    public long getLoopIntervalMs() {
        return loopIntervalMs;
    }

    public void setLoopIntervalMs(long loopIntervalMs) {
        this.loopIntervalMs = loopIntervalMs;
    }

    public NodeCondition getLoopBreakCondition() {
        return loopBreakCondition;
    }

    public void setLoopBreakCondition(NodeCondition loopBreakCondition) {
        this.loopBreakCondition = loopBreakCondition;
    }

    public int getMaxLoopCount() {
        return maxLoopCount;
    }

    public void setMaxLoopCount(int maxLoopCount) {
        this.maxLoopCount = maxLoopCount;
    }

    public List<Parameter> getParameters() {
        return null;
    }

    public long getComputeCost() {
        return computeCost;
    }

    public void setComputeCost(long computeCost) {
        this.computeCost = computeCost;
    }

    public ChainNodeValidResult validate() throws Exception {
        return ChainNodeValidResult.ok();
    }

    protected abstract Map<String, Object> execute(Chain chain);

}
