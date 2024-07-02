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
import com.agentsflex.core.agent.Output;
import com.agentsflex.core.chain.event.*;
import com.agentsflex.core.chain.node.AgentNode;
import com.agentsflex.core.memory.ContextMemory;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.StringUtil;

import java.util.*;


public class Chain extends ChainNode {
    protected Map<Class<?>, List<ChainEventListener>> eventListeners = new HashMap<>(0);
    protected List<ChainOutputListener> outputListeners = new ArrayList<>();
    protected List<ChainNode> nodes;
    protected List<ChainLine> lines;
    protected ChainStatus status = ChainStatus.READY;
    protected String message;
    protected Chain parent;
    protected List<Chain> children;


    public Chain() {
        this.id = UUID.randomUUID().toString();
    }


    public Map<Class<?>, List<ChainEventListener>> getEventListeners() {
        return eventListeners;
    }

    public void setEventListeners(Map<Class<?>, List<ChainEventListener>> eventListeners) {
        this.eventListeners = eventListeners;
    }

    public synchronized void registerEventListener(Class<? extends ChainEvent> eventClass, ChainEventListener listener) {
        List<ChainEventListener> chainEventListeners = eventListeners.computeIfAbsent(eventClass, k -> new ArrayList<>());
        chainEventListeners.add(listener);
    }

    public synchronized void registerEventListener(ChainEventListener listener) {
        List<ChainEventListener> chainEventListeners = eventListeners.computeIfAbsent(ChainEvent.class, k -> new ArrayList<>());
        chainEventListeners.add(listener);
    }


    public synchronized void removeEventListener(ChainEventListener listener) {
        for (List<ChainEventListener> list : eventListeners.values()) {
            list.removeIf(item -> item == listener);
        }
    }

    public synchronized void removeEventListener(Class<? extends ChainEvent> eventClass, ChainEventListener listener) {
        List<ChainEventListener> list = eventListeners.get(eventClass);
        if (list != null && !list.isEmpty()) {
            list.removeIf(item -> item == listener);
        }
    }


    public List<ChainOutputListener> getOutputListeners() {
        return outputListeners;
    }

    public void setOutputListeners(List<ChainOutputListener> outputListeners) {
        this.outputListeners = outputListeners;
    }

    public void registerOutputListener(ChainOutputListener outputListener) {
        if (this.outputListeners == null) {
            this.outputListeners = new ArrayList<>();
        }
        this.outputListeners.add(outputListener);
    }


    public List<ChainNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<ChainNode> chainNodes) {
        this.nodes = chainNodes;
    }

    public void addNode(ChainNode chainNode) {
        if (nodes == null) {
            this.nodes = new ArrayList<>();
        }

        if (chainNode instanceof ChainEventListener) {
            registerEventListener((ChainEventListener) chainNode);
        }

        if (chainNode.getId() == null) {
            chainNode.setId(UUID.randomUUID().toString());
        }

        if (chainNode instanceof Chain) {
            ((Chain) chainNode).parent = this;
            this.addChild((Chain) chainNode);
        }

        nodes.add(chainNode);
    }

    private void addChild(Chain child) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(child);
    }

    public void addNode(Agent agent) {
        addNode(new AgentNode(agent));
    }


    public ContextMemory getMemory() {
        return memory;
    }


    public void setMemory(ContextMemory memory) {
        this.memory = memory;
    }

    public ChainStatus getStatus() {
        return status;
    }

    public void setStatus(ChainStatus status) {
        ChainStatus before = this.status;
        this.status = status;

        if (before != status) {
            notifyEvent(new OnStatusChangeEvent(this.status, before));
        }
    }

    public Chain getParent() {
        return parent;
    }

    public void setParent(Chain parent) {
        this.parent = parent;
    }

    public List<Chain> getChildren() {
        return children;
    }

    public void setChildren(List<Chain> children) {
        this.children = children;
    }


    public void notifyEvent(ChainEvent event) {
        for (Map.Entry<Class<?>, List<ChainEventListener>> entry : eventListeners.entrySet()) {
            if (entry.getKey().isInstance(event)) {
                for (ChainEventListener chainEventListener : entry.getValue()) {
                    chainEventListener.onEvent(event, this);
                }
            }
        }
        if (parent != null) parent.notifyEvent(event);
    }

    public Object get(String key) {
        return this.memory.get(key);
    }

    public Object getGlobal(String key) {
        return this.memory.get(key);
    }

    @Override
    protected Map<String, Object> execute(Chain parent) {
        this.execute(parent.getMemory().getAll());
        return this.memory.getAll();
    }


    public void execute(Object variable) {
        this.execute(Output.DEFAULT_VALUE_KEY, variable);
    }

    public void execute(String key, Object variable) {
        Map<String, Object> variables = new HashMap<>(1);
        variables.put(key, variable);
        this.execute(variables);
    }

    public <T> T executeForResult(Object variable) throws ChainException {
        return executeForResult(Output.DEFAULT_VALUE_KEY, variable);
    }

    public <T> T executeForResult(String key, Object variable) throws ChainException {
        Map<String, Object> variables = new HashMap<>(1);
        variables.put(key, variable);
        this.execute(variables);

        if (this.status != ChainStatus.FINISHED_NORMAL) {
            throw new ChainException(this.message);
        }
        //noinspection unchecked
        return (T) this.getMemory().get(Output.DEFAULT_VALUE_KEY);
    }


    public void execute(Map<String, Object> variables) {
        runInLifeCycle(variables, this::executeInternal);
    }


    protected void executeInternal() {
        List<ChainNode> currentNodes = getStartNodes();
        while (CollectionUtil.hasItems(currentNodes)) {
            ChainNode currentNode = currentNodes.remove(0);

            Integer execCount = (Integer) currentNode.getMemory().get("_exec_count");
            if (execCount == null) execCount = 0;

            ChainCondition nodeCondition = currentNode.getCondition();
            if (nodeCondition != null && !nodeCondition.check(this, this.getMemory().getAll())) {
                continue;
            }

            Map<String, Object> executeResult = null;
            try {
                ChainContext.setNode(currentNode);
                notifyEvent(new OnNodeStartEvent(currentNode));
                if (this.getStatus() != ChainStatus.RUNNING) {
                    break;
                }
                executeResult = currentNode.execute(this);
            } finally {
                ChainContext.clearNode();
                currentNode.getMemory().put("_exec_count", execCount + 1);
                notifyEvent(new OnNodeFinishedEvent(currentNode, executeResult));
            }

            if (executeResult != null && !executeResult.isEmpty()) {
                this.memory.putAll(executeResult);
            }

            if (this.getStatus() != ChainStatus.RUNNING) {
                break;
            }

            List<ChainLine> linesOut = currentNode.getLinesOut();

            if (CollectionUtil.hasItems(linesOut)) {
                for (ChainLine chainLine : linesOut) {
                    ChainNode nextNode = getNodeById(chainLine.getTarget());
                    if (nextNode == null) {
                        continue;
                    }
                    ChainCondition condition = chainLine.getCondition();
                    if (condition == null) {
                        currentNodes.add(nextNode);
                    } else if (condition.check(this, this.memory.getAll())) {
                        currentNodes.add(nextNode);
                    }
                }
            }
        }
    }


    private List<ChainNode> getStartNodes() {
        if (this.nodes == null || this.nodes.isEmpty()) {
            return null;
        }

        List<ChainNode> nodes = new ArrayList<>();

        for (ChainNode node : this.nodes) {
            if (CollectionUtil.noItems(node.getLinesIn())) {
                nodes.add(node);
            }
        }

        return nodes;
    }


    private ChainNode getNodeById(String id) {
        if (id == null || StringUtil.noText(id)) {
            return null;
        }

        for (ChainNode node : this.nodes) {
            if (id.equals(node.getId())) {
                return node;
            }
        }

        return null;
    }


    protected void runInLifeCycle(Map<String, Object> variables, Runnable runnable) {
        if (variables != null) {
            this.memory.putAll(variables);
        }
        try {
            ChainContext.setChain(this);
            notifyEvent(new OnStartEvent());
            try {
                setStatus(ChainStatus.RUNNING);
                runnable.run();
            } catch (Exception e) {
                setStatus(ChainStatus.ERROR);
                notifyEvent(new OnErrorEvent(e));
            }
            if (status == ChainStatus.RUNNING) {
                setStatus(ChainStatus.FINISHED_NORMAL);
            } else if (status == ChainStatus.ERROR) {
                setStatus(ChainStatus.FINISHED_ABNORMAL);
            }
        } finally {
            ChainContext.clearChain();
            notifyEvent(new OnFinishedEvent());
        }
    }


    private void notifyOutput(Agent agent, Object response) {
        for (ChainOutputListener inputListener : outputListeners) {
            inputListener.onOutput(this, agent, response);
        }
        if (parent != null) parent.notifyOutput(agent, response);
    }

    public void stopNormal(String message) {
        this.message = message;
        setStatus(ChainStatus.FINISHED_NORMAL);
    }

    public void stopError(String message) {
        this.message = message;
        setStatus(ChainStatus.FINISHED_ABNORMAL);
    }

    public void output(Agent agent, Object response) {
        notifyOutput(agent, response);
    }

    public String getMessage() {
        return message;
    }


    public List<ChainLine> getLines() {
        return lines;
    }

    public void setLines(List<ChainLine> lines) {
        this.lines = lines;
    }

    public void addLine(ChainLine line) {
        if (this.lines == null) {
            this.lines = new ArrayList<>();
        }
        this.lines.add(line);

        boolean findSource = false, findTarget = false;

        for (ChainNode node : this.nodes) {
            if (node.getId().equals(line.getSource())) {
                node.addLineOut(line);
                findSource = true;
            } else if (node.getId().equals(line.getTarget())) {
                node.addLineIn(line);
                findTarget = true;
            }
            if (findSource && findTarget) {
                break;
            }
        }
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "Chain{" +
            "id='" + id + '\'' +
            ", memory=" + memory +
            ", eventListeners=" + eventListeners +
            ", outputListeners=" + outputListeners +
            ", nodes=" + nodes +
            ", lines=" + lines +
            ", status=" + status +
            ", message='" + message + '\'' +
            '}';
    }
}
