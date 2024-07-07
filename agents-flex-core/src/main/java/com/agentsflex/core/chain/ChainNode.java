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
    protected boolean async;
    protected List<ChainLine> linesIn;
    protected List<ChainLine> linesOut;

    protected ChainCondition condition;

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

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public List<ChainLine> getLinesIn() {
        return linesIn;
    }

    public void setLinesIn(List<ChainLine> linesIn) {
        this.linesIn = linesIn;
    }

    public List<ChainLine> getLinesOut() {
        return linesOut;
    }

    public void setLinesOut(List<ChainLine> linesOut) {
        this.linesOut = linesOut;
    }

    public ChainCondition getCondition() {
        return condition;
    }

    public void setCondition(ChainCondition condition) {
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

    protected void addLineOut(ChainLine line) {
        if (this.linesOut == null) {
            this.linesOut = new ArrayList<>();
        }
        this.linesOut.add(line);
    }

    protected void addLineIn(ChainLine line) {
        if (this.linesIn == null) {
            this.linesIn = new ArrayList<>();
        }
        this.linesIn.add(line);
    }
}
