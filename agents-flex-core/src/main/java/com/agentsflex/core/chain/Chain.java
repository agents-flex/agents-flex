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

import com.agentsflex.core.chain.event.*;
import com.agentsflex.core.chain.node.BaseNode;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.NamedThreadPools;
import com.agentsflex.core.util.StringUtil;

import java.util.*;
import java.util.concurrent.ExecutorService;


public class Chain extends ChainNode {
    public static final String CTX_EXEC_COUNT = "_exec_count";
    protected Map<Class<?>, List<ChainEventListener>> eventListeners = new HashMap<>(0);
    protected Map<String, Object> executeResult = null;
    protected List<ChainOutputListener> outputListeners = new ArrayList<>();
    protected List<ChainNode> nodes;
    protected List<ChainEdge> edges;
    protected ChainStatus status = ChainStatus.READY;
    protected String message;
    protected Chain parent;
    protected List<Chain> children;
    protected ExecutorService asyncNodeExecutors = NamedThreadPools.newFixedThreadPool("chain-executor");


    public Chain() {
        this.id = UUID.randomUUID().toString();
    }


    public Map<Class<?>, List<ChainEventListener>> getEventListeners() {
        return eventListeners;
    }

    public void setEventListeners(Map<Class<?>, List<ChainEventListener>> eventListeners) {
        this.eventListeners = eventListeners;
    }

    public synchronized void addEventListener(Class<? extends ChainEvent> eventClass, ChainEventListener listener) {
        List<ChainEventListener> chainEventListeners = eventListeners.computeIfAbsent(eventClass, k -> new ArrayList<>());
        chainEventListeners.add(listener);
    }

    public synchronized void addEventListener(ChainEventListener listener) {
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

    public void addOutputListener(ChainOutputListener outputListener) {
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
            addEventListener((ChainEventListener) chainNode);
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


    public void execute(Map<String, Object> variables) {
        runInLifeCycle(variables, this::executeInternal);
    }


    public Map<String, Object> executeForResult(Map<String, Object> variables) {
        runInLifeCycle(variables, this::executeInternal);
        return this.executeResult;
    }


    public List<InputParameter> getInputParameters() {
        List<ChainNode> startNodes = this.getStartNodes();
        if (startNodes == null || startNodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<InputParameter> inputParameters = new ArrayList<>();
        for (ChainNode node : startNodes) {
            if (node instanceof BaseNode) {
                List<InputParameter> nodeInputParameters = ((BaseNode) node).getInputParameters();
                if (nodeInputParameters != null) inputParameters.addAll(nodeInputParameters);
            } else if (node instanceof Chain) {
                List<InputParameter> chainInputParameters = ((Chain) node).getInputParameters();
                if (chainInputParameters != null) inputParameters.addAll(chainInputParameters);
            }
        }
        return inputParameters;
    }

    protected void executeInternal() {
        List<ChainNode> currentNodes = getStartNodes();
        while (CollectionUtil.hasItems(currentNodes)) {
            ChainNode currentNode = currentNodes.remove(0);

            Integer execCount = (Integer) currentNode.getMemory().get(CTX_EXEC_COUNT);
            if (execCount == null) execCount = 0;

            NodeCondition nodeCondition = currentNode.getCondition();
            if (nodeCondition != null && !nodeCondition.check(this, currentNode)) {
                continue;
            }

            Map<String, Object> executeResult = null;
            try {
                ChainContext.setNode(currentNode);
                notifyEvent(new OnNodeStartEvent(currentNode));
                if (this.getStatus() != ChainStatus.RUNNING) {
                    break;
                }
                executeResult = executeNode(currentNode);
                this.executeResult = executeResult;
            } finally {
                ChainContext.clearNode();
                currentNode.getMemory().put(CTX_EXEC_COUNT, execCount + 1);
                notifyEvent(new OnNodeFinishedEvent(currentNode, executeResult));
            }

            if (executeResult != null && !executeResult.isEmpty()) {
                executeResult.forEach((s, o) -> {
                    Chain.this.memory.put(currentNode.id + "." + s, o);
                });
            }

            if (this.getStatus() != ChainStatus.RUNNING) {
                break;
            }

            List<ChainEdge> outwardEdges = currentNode.getOutwardEdges();

            if (CollectionUtil.hasItems(outwardEdges)) {
                for (ChainEdge chainEdge : outwardEdges) {
                    ChainNode nextNode = getNodeById(chainEdge.getTarget());
                    if (nextNode == null) {
                        continue;
                    }
                    EdgeCondition condition = chainEdge.getCondition();
                    if (condition == null) {
                        currentNodes.add(nextNode);
                    } else if (condition.check(this, chainEdge)) {
                        currentNodes.add(nextNode);
                    }
                }
            }
        }
    }


    private Map<String, Object> executeNode(ChainNode currentNode) {
        Map<String, Object> executeResult = null;
        if (currentNode.isAsync()) {
            asyncNodeExecutors.execute(() -> currentNode.execute(Chain.this));
        } else {
            executeResult = currentNode.execute(this);
        }
        return executeResult;
    }


    private List<ChainNode> getStartNodes() {
        if (this.nodes == null || this.nodes.isEmpty()) {
            return null;
        }

        List<ChainNode> nodes = new ArrayList<>();

        for (ChainNode node : this.nodes) {
            if (CollectionUtil.noItems(node.getInwardEdges())) {
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


    private void notifyOutput(ChainNode node, Object response) {
        for (ChainOutputListener inputListener : outputListeners) {
            inputListener.onOutput(this, node, response);
        }
        if (parent != null) parent.notifyOutput(node, response);
    }

    public void stopNormal(String message) {
        this.message = message;
        setStatus(ChainStatus.FINISHED_NORMAL);
    }

    public void stopError(String message) {
        this.message = message;
        setStatus(ChainStatus.FINISHED_ABNORMAL);
    }

    public void output(ChainNode node, Object response) {
        notifyOutput(node, response);
    }

    public String getMessage() {
        return message;
    }


    public List<ChainEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<ChainEdge> edges) {
        this.edges = edges;
    }

    public void addEdge(ChainEdge edge) {
        if (this.edges == null) {
            this.edges = new ArrayList<>();
        }
        this.edges.add(edge);

        boolean findSource = false, findTarget = false;

        for (ChainNode node : this.nodes) {
            if (node.getId().equals(edge.getSource())) {
                node.addOutwardEdge(edge);
                findSource = true;
            } else if (node.getId().equals(edge.getTarget())) {
                node.addInwardEdge(edge);
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

    public ExecutorService getAsyncNodeExecutors() {
        return asyncNodeExecutors;
    }

    public void setAsyncNodeExecutors(ExecutorService asyncNodeExecutors) {
        this.asyncNodeExecutors = asyncNodeExecutors;
    }

    @Override
    public String toString() {
        return "Chain{" +
            "id='" + id + '\'' +
            ", memory=" + memory +
            ", eventListeners=" + eventListeners +
            ", outputListeners=" + outputListeners +
            ", nodes=" + nodes +
            ", lines=" + edges +
            ", status=" + status +
            ", message='" + message + '\'' +
            '}';
    }
}
