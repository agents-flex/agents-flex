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
import com.agentsflex.core.util.MapUtil;
import com.agentsflex.core.util.NamedThreadPools;
import com.agentsflex.core.util.StringUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;


public class Chain extends ChainNode {
    protected Chain parent;
    protected List<Chain> children;

    protected List<ChainNode> nodes;
    protected List<ChainEdge> edges;

    protected Map<Class<?>, List<ChainEventListener>> eventListeners = new HashMap<>(0);
    protected Map<String, Object> executeResult = null;
    protected List<ChainOutputListener> outputListeners = new ArrayList<>();
    protected List<ChainErrorListener> errorListeners = new ArrayList<>();
    protected List<ChainSuspendListener> suspendListeners = new ArrayList<>();


    protected ExecutorService asyncNodeExecutors = NamedThreadPools.newFixedThreadPool("chain-executor");
    protected Map<String, NodeContext> nodeContexts = new ConcurrentHashMap<>();

    protected List<ChainNode> suspendNodes;
    protected List<Parameter> suspendForParameters;
    protected ChainStatus status = ChainStatus.READY;
    protected Exception exception;
    protected String message;


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

    public synchronized void addErrorListener(ChainErrorListener listener) {
        this.errorListeners.add(listener);
    }

    public synchronized void removeErrorListener(ChainErrorListener listener) {
        this.errorListeners.remove(listener);
    }

    public synchronized void addSuspendListener(ChainSuspendListener listener) {
        this.suspendListeners.add(listener);
    }

    public synchronized void removeSuspendListener(ChainSuspendListener listener) {
        this.suspendListeners.remove(listener);
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
            notifyEvent(new ChainStatusChangeEvent(this, this.status, before));
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
        runInLifeCycle(variables,
            new ChainStartEvent(this),
            this::executeInternal);
    }


    public Map<String, Object> executeForResult(Map<String, Object> variables) {

        runInLifeCycle(variables, new ChainStartEvent(this), this::executeInternal);

        if (this.status == ChainStatus.FINISHED_ABNORMAL) {
            if (this.exception != null) {
                if (this.exception instanceof RuntimeException) {
                    throw (RuntimeException) this.exception;
                } else {
                    throw new ChainException(this.exception);
                }
            } else {
                if (this.message == null) this.message = "Chain execute error";
                throw new ChainException(this.message);
            }
        }

        return this.executeResult;
    }


    public List<Parameter> getParameters() {
        List<ChainNode> startNodes = this.getStartNodes();
        if (startNodes == null || startNodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<Parameter> parameters = new ArrayList<>();
        for (ChainNode node : startNodes) {
            if (node instanceof BaseNode) {
                List<Parameter> nodeParameters = ((BaseNode) node).getParameters();
                if (nodeParameters != null) parameters.addAll(nodeParameters);
            } else if (node instanceof Chain) {
                List<Parameter> chainParameters = ((Chain) node).getParameters();
                if (chainParameters != null) parameters.addAll(chainParameters);
            }
        }
        return parameters;
    }

    public Map<String, Object> getParameterValues(BaseNode node) {
        return getParameterValues(node, node.getParameters());
    }

    public Map<String, Object> getParameterValues(BaseNode node, List<Parameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> variables = new HashMap<>();
        for (Parameter parameter : parameters) {
            RefType refType = parameter.getRefType();
            Object value;
            if (refType == RefType.FIXED) {
                value = parameter.getValue();
            } else if (refType == RefType.REF) {
                value = this.get(parameter.getRef());
                if (value == null && parameter.getDefaultValue() != null) {
                    value = parameter.getDefaultValue();
                }
            } else {
                value = this.get(parameter.getName());
            }

            if (parameter.isRequired() &&
                (value == null || (value instanceof String && StringUtil.noText((String) value)))) {
                if (refType == RefType.FIXED || refType == RefType.REF) {
                    throw new ChainException(node.getName() + " Missing required parameter:" + parameter.getName());
                } else {
                    this.addSuspendForParameter(parameter);
                    this.suspend(node);
                    throw new ChainSuspendException(node.getClass() + " Missing required parameter:" + parameter.getName());
                }
            }

            if (value == null || value instanceof String) {
                value = value == null ? "" : ((String) value).trim();
                if (parameter.getDataType() == DataType.Boolean) {
                    value = "true".equalsIgnoreCase((String) value) || "1".equalsIgnoreCase((String) value);
                } else if (parameter.getDataType() == DataType.Number) {
                    value = Long.parseLong((String) value);
                }
            }

            variables.put(parameter.getName(), value);
        }
        return variables;
    }

    public NodeContext getNodeContext(String nodeId) {
        return MapUtil.computeIfAbsent(nodeContexts, nodeId, k -> new NodeContext());
    }

    protected void executeInternal() {
        List<ChainNode> currentNodes = getStartNodes();
        if (currentNodes == null || currentNodes.isEmpty()) {
            return;
        }

        List<ExecuteNode> waitingExecuteNodes = new ArrayList<>();
        for (ChainNode currentNode : currentNodes) {
            waitingExecuteNodes.add(new ExecuteNode(currentNode, null, ""));
        }

        while (CollectionUtil.hasItems(waitingExecuteNodes)) {
            ExecuteNode executeNode = waitingExecuteNodes.remove(0);
            ChainNode currentNode = executeNode.currentNode;
            NodeContext nodeContext = getNodeContext(currentNode.getId());
            try {
                onNodeExecuteBefore(nodeContext);

                nodeContext.recordTrigger(executeNode);

                NodeCondition nodeCondition = currentNode.getCondition();
                if (nodeCondition != null && !nodeCondition.check(this, nodeContext)) {
                    continue;
                }

                Map<String, Object> executeResult = null;
                try {
                    ChainContext.setNode(currentNode);
                    notifyEvent(new NodeStartEvent(this, currentNode));
                    if (this.getStatus() != ChainStatus.RUNNING) {
                        break;
                    }
                    onNodeExecuteStart(nodeContext);
                    nodeContext.recordExecute(executeNode);
                    executeResult = executeNode(currentNode);
                    this.executeResult = executeResult;
                } finally {
                    onNodeExecuteEnd(nodeContext);
                    ChainContext.clearNode();
                    notifyEvent(new NodeEndEvent(this, currentNode, executeResult));
                }

                if (executeResult != null && !executeResult.isEmpty()) {
                    executeResult.forEach((s, o) -> {
                        Chain.this.memory.put(currentNode.id + "." + s, o);
                    });
                }
            } finally {
                onNodeExecuteAfter(nodeContext);
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
                    if (condition == null || condition.check(this, chainEdge)) {
                        waitingExecuteNodes.add(new ExecuteNode(nextNode, currentNode, chainEdge.getId()));
                    }
                }
            }
        }
    }

    protected void onNodeExecuteAfter(NodeContext nodeContext) {

    }

    protected void onNodeExecuteEnd(NodeContext nodeContext) {

    }

    protected void onNodeExecuteStart(NodeContext nodeContext) {

    }

    protected void onNodeExecuteBefore(NodeContext nodeContext) {

    }


    private Map<String, Object> executeNode(ChainNode currentNode) {
        if (currentNode.isAsync()) {
            Chain currentChain = ChainContext.getCurrentChain();
            Future<Map<String, Object>> future = asyncNodeExecutors.submit(() -> {
                try {
                    ChainContext.setChain(currentChain);
                    ChainContext.setNode(currentNode);
                    return currentNode.execute(Chain.this);
                } finally {
                    ChainContext.clearNode();
                    ChainContext.clearChain();
                }
            });
            if (currentNode.isAwaitAsyncResult()) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof ChainSuspendException) {
                        throw (ChainSuspendException) e.getCause();
                    } else if (e.getCause() instanceof RuntimeException) {
                        throw (RuntimeException) e.getCause();
                    } else {
                        throw new RuntimeException(e.getCause());
                    }
                }
            }
        }
        // not async node
        else {
            return currentNode.execute(this);
        }
        return null;
    }


    private List<ChainNode> getStartNodes() {
        if (this.nodes == null || this.nodes.isEmpty()) {
            return null;
        }

        if (CollectionUtil.hasItems(this.suspendNodes)) {
            return this.suspendNodes;
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


    protected void runInLifeCycle(Map<String, Object> variables, ChainEvent startEvent, Runnable runnable) {
        if (variables != null) {
            this.memory.putAll(variables);
        }
        try {
            ChainContext.setChain(this);
            notifyEvent(startEvent);
            try {
                setStatus(ChainStatus.RUNNING);
                runnable.run();
            } catch (ChainSuspendException cse) {
                notifySuspend();
            } catch (Exception e) {
                this.exception = e;
                setStatus(ChainStatus.ERROR);
                notifyError(e);
            }
            if (status == ChainStatus.RUNNING) {
                setStatus(ChainStatus.FINISHED_NORMAL);
            } else if (status == ChainStatus.ERROR) {
                setStatus(ChainStatus.FINISHED_ABNORMAL);
            }
        } finally {
            ChainContext.clearChain();
            notifyEvent(new ChainEndEvent(this));
        }
    }


    private void notifyOutput(ChainNode node, Object response) {
        for (ChainOutputListener inputListener : outputListeners) {
            inputListener.onOutput(this, node, response);
        }
        if (parent != null) parent.notifyOutput(node, response);
    }


    private void notifySuspend() {
        for (ChainSuspendListener suspendListener : suspendListeners) {
            suspendListener.onSuspend(this);
        }
        if (parent != null) parent.notifySuspend();
    }


    private void notifyError(Throwable error) {
        for (ChainErrorListener errorListener : errorListeners) {
            errorListener.onError(this, error);
        }
        if (parent != null) parent.notifyError(error);
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

    public List<Parameter> getSuspendForParameters() {
        return suspendForParameters;
    }

    public void setSuspendForParameters(List<Parameter> suspendForParameters) {
        this.suspendForParameters = suspendForParameters;
    }

    public void addSuspendForParameter(Parameter suspendForParameter) {
        if (this.suspendForParameters == null) {
            this.suspendForParameters = new ArrayList<>();
        }
        this.suspendForParameters.add(suspendForParameter);
    }

    public void suspend(ChainNode node) {
        try {
            if (suspendNodes == null) {
                suspendNodes = new ArrayList<>();
            }
            if (!suspendNodes.contains(node)) {
                suspendNodes.add(node);
            }
        } finally {
            setStatus(ChainStatus.SUSPEND);
        }
    }

    public void resume(Map<String, Object> variables) {
        runInLifeCycle(variables,
            new ChainResumeEvent(this),
            this::executeInternal);
    }


    public static class ExecuteNode {

        final ChainNode currentNode;
        final ChainNode prevNode;
        final String fromEdgeId;

        public ExecuteNode(ChainNode currentNode, ChainNode prevNode, String fromEdgeId) {
            this.currentNode = currentNode;
            this.prevNode = prevNode;
            this.fromEdgeId = fromEdgeId;
        }
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
