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
import com.agentsflex.core.prompt.template.TextPromptTemplate;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.MapUtil;
import com.agentsflex.core.util.NamedThreadPools;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSONPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.stream.Collectors;


public class Chain extends ChainNode {
    private static final Logger log = LoggerFactory.getLogger(Chain.class);
    protected Chain parent;
    protected List<Chain> children;

    protected List<ChainNode> nodes;
    protected List<ChainEdge> edges;

    protected Map<String, Object> executeResult = null;

    protected Map<Class<?>, List<ChainEventListener>> eventListeners = new HashMap<>(0);
    protected List<ChainOutputListener> outputListeners = new ArrayList<>();
    protected List<ChainErrorListener> chainErrorListeners = new ArrayList<>();
    protected List<NodeErrorListener> nodeErrorListeners = new ArrayList<>();
    protected List<ChainSuspendListener> suspendListeners = new ArrayList<>();


    protected ExecutorService asyncNodeExecutors = NamedThreadPools.newFixedThreadPool("chain-executor");
    protected Phaser phaser = new Phaser(1);
    protected Map<String, NodeContext> nodeContexts = new ConcurrentHashMap<>();

    protected Map<String,ChainNode> suspendNodes=new ConcurrentHashMap<>();
    protected List<Parameter> suspendForParameters;
    protected ChainStatus status = ChainStatus.READY;
    protected Exception exception;
    protected String message;


    public Chain() {
        this.id = UUID.randomUUID().toString();
    }

    public Chain(ChainHolder holder) {
        this.id = holder.getId();
        this.name = holder.getName();
        this.description = holder.getDescription();
        this.parent = holder.getParent() == null ? null : new Chain(holder.getParent());
        this.children = holder.getChildren() == null ? null : holder.getChildren().stream().map(Chain::new)
            .collect(Collectors.toList());

        this.nodes = holder.getNodes();
        this.edges = holder.getEdges();

        this.executeResult = holder.getExecuteResult();
        this.nodeContexts = holder.getNodeContexts();
        this.suspendNodes = holder.getSuspendNodes();
        this.suspendForParameters = holder.getSuspendForParameters();
        this.status = holder.getStatus();
        this.message = holder.getMessage();
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
        this.chainErrorListeners.add(listener);
    }

    public synchronized void removeErrorListener(ChainErrorListener listener) {
        this.chainErrorListeners.remove(listener);
    }

    public synchronized void addNodeErrorListener(NodeErrorListener listener) {
        this.nodeErrorListeners.add(listener);
    }

    public synchronized void removeNodeErrorListener(NodeErrorListener listener) {
        this.nodeErrorListeners.remove(listener);
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
        this.status = status;
    }

    public void setStatusAndNotifyEvent(ChainStatus status) {
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


    public Map<String, Object> getExecuteResult() {
        return executeResult;
    }

    public void setExecuteResult(Map<String, Object> executeResult) {
        this.executeResult = executeResult;
    }

    public List<ChainErrorListener> getChainErrorListeners() {
        return chainErrorListeners;
    }

    public void setChainErrorListeners(List<ChainErrorListener> chainErrorListeners) {
        this.chainErrorListeners = chainErrorListeners;
    }

    public List<NodeErrorListener> getNodeErrorListeners() {
        return nodeErrorListeners;
    }

    public void setNodeErrorListeners(List<NodeErrorListener> nodeErrorListeners) {
        this.nodeErrorListeners = nodeErrorListeners;
    }

    public List<ChainSuspendListener> getSuspendListeners() {
        return suspendListeners;
    }

    public void setSuspendListeners(List<ChainSuspendListener> suspendListeners) {
        this.suspendListeners = suspendListeners;
    }

    public Map<String, NodeContext> getNodeContexts() {
        return nodeContexts;
    }

    public void setNodeContexts(Map<String, NodeContext> nodeContexts) {
        this.nodeContexts = nodeContexts;
    }

    public Map<String, ChainNode> getSuspendNodes() {
        return suspendNodes;
    }

    public void setSuspendNodes(Map<String, ChainNode> suspendNodes) {
        this.suspendNodes = suspendNodes;
    }

    public Exception getException() {
        return exception;
    }

    public void setException(Exception exception) {
        this.exception = exception;
    }

    public Phaser getPhaser() {
        return phaser;
    }

    public void setPhaser(Phaser phaser) {
        this.phaser = phaser;
    }

    public void set(String key, Object value) {
        this.memory.put(key, value);
    }


    public Object get(String key) {
        if (StringUtil.noText(key)) {
            return null;
        }

        Object result = memory.get(key);
        if (result != null) {
            return result;
        }

        List<String> parts = Arrays.asList(key.split("\\."));
        if (parts.isEmpty()) {
            return null;
        }

        int matchedLevels = 0;
        for (int i = parts.size(); i > 0; i--) {
            String tryKey = String.join(".", parts.subList(0, i));
            Object tempResult = memory.get(tryKey);
            if (tempResult != null) {
                result = tempResult;
                matchedLevels = i;
                break;
            }
        }

        if (result == null) {
            return null;
        }

        if (result instanceof Collection) {
            List<Object> results = new ArrayList<>();
            for (Object item : ((Collection<?>) result)) {
                results.add(getResult(parts, matchedLevels, item));
            }
            return results;
        }

        return getResult(parts, matchedLevels, result);

    }

    private static Object getResult(List<String> parts, int matchedLevels, Object result) {
        List<String> remainingParts = parts.subList(matchedLevels, parts.size());
        String jsonPath = "$." + String.join(".", remainingParts);
        try {
            return JSONPath.eval(result, jsonPath);
        } catch (Exception e) {
            log.error(e.toString(), e);
        }

        return null;
    }


    @Override
    protected Map<String, Object> execute(Chain parent) {
        return executeForResult(parent.getMemory().getAll());
    }

    public void execute(Map<String, Object> variables) {
        runInLifeCycle(variables,
            new ChainStartEvent(this, variables),
            this::executeInternal);
    }


    public Map<String, Object> executeForResult(Map<String, Object> variables) {
        return executeForResult(variables, false);
    }

    public Map<String, Object> executeForResult(Map<String, Object> variables, boolean ignoreError) {
        if (this.status == ChainStatus.SUSPEND) {
            this.resume(variables);
        } else {
            runInLifeCycle(variables, new ChainStartEvent(this, variables), this::executeInternal);
        }

        if (!ignoreError) {
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
            } else if (this.status == ChainStatus.SUSPEND && this.exception != null) {
                throw (ChainSuspendException) this.exception;
            }
        }

        return this.executeResult;
    }


    @Override
    public List<Parameter> getParameters() {
        List<ChainNode> startNodes = this.getStartNodes();
        if (startNodes == null || startNodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<Parameter> parameters = new ArrayList<>();
        for (ChainNode node : startNodes) {
            List<Parameter> nodeParameters = node.getParameters();
            if (nodeParameters != null) parameters.addAll(nodeParameters);
        }
        return parameters;
    }

    public Map<String, Object> getParameterValues(ChainNode node) {
        return getParameterValues(node, node.getParameters());
    }

    public Map<String, Object> getParameterValues(ChainNode node, List<Parameter> parameters) {
        return getParameterValues(node, parameters, null);
    }

    public Map<String, Object> getParameterValues(ChainNode node, List<Parameter> parameters, Map<String, Object> formatArgs) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> variables = new HashMap<>();
        for (Parameter parameter : parameters) {
            RefType refType = parameter.getRefType();
            Object value;
            if (refType == RefType.FIXED) {
                if (formatArgs != null && !formatArgs.isEmpty()) {
                    value = TextPromptTemplate.of(parameter.getValue()).formatToString(formatArgs);
                } else {
                    value = parameter.getValue();
                }
            } else if (refType == RefType.REF) {
                value = this.get(parameter.getRef());
            } else {
                value = this.get(parameter.getName());
            }

            if (value == null && parameter.getDefaultValue() != null) {
                value = parameter.getDefaultValue();
            }

            if (parameter.isRequired() &&
                (value == null || (value instanceof String && StringUtil.noText((String) value)))) {
                if (refType == RefType.INPUT) {
                    this.addSuspendForParameter(parameter);
                    this.suspend(node);
                    throw new ChainSuspendException(node.getClass() + " Missing required parameter:" + parameter.getName());
                }
                // else if (refType == RefType.FIXED || refType == RefType.REF) {
                else {
                    throw new ChainException(node.getName() + " Missing required parameter:" + parameter.getName());
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

    public NodeContext getNodeContext(ChainNode chainNode) {
        return MapUtil.computeIfAbsent(nodeContexts, chainNode.getId(), k -> new NodeContext());
    }

    protected void executeInternal() {
        List<ChainNode> currentNodes = getStartNodes();
        if (currentNodes == null || currentNodes.isEmpty()) {
            return;
        }

        List<ExecuteNode> executeNodes = new ArrayList<>();
        for (ChainNode currentNode : currentNodes) {
            executeNodes.add(new ExecuteNode(currentNode, null, ""));
        }

        doExecuteNodes(executeNodes);
    }


    protected void doExecuteNodes(List<ExecuteNode> executeNodes) {
        for (ExecuteNode executeNode : executeNodes) {
            ChainNode currentNode = executeNode.currentNode;
            if (currentNode.isAsync()) {
                phaser.register();
                asyncNodeExecutors.execute(() -> {
                    try {
                        doExecuteNode(executeNode);
                    } finally {
                        phaser.arriveAndDeregister();
                    }
                });
            } else {
                doExecuteNode(executeNode);
            }
        }
    }


    protected void doExecuteNode(ExecuteNode executeNode) {
        if (this.getStatus() != ChainStatus.RUNNING) {
            return;
        }
        ChainNode currentNode = executeNode.currentNode;
        NodeContext nodeContext = getNodeContext(currentNode);
        try {
            onNodeExecuteBefore(nodeContext);
            nodeContext.recordTrigger(executeNode);
            NodeCondition nodeCondition = currentNode.getCondition();
            if (nodeCondition != null && !nodeCondition.check(this, nodeContext)) {
                return;
            }

            Map<String, Object> executeResult = null;
            try {
                ChainContext.setNode(currentNode);
                notifyEvent(new NodeStartEvent(this, currentNode));
                if (this.getStatus() != ChainStatus.RUNNING) {
                    return;
                }

                currentNode.setNodeStatus(ChainNodeStatus.RUNNING);
                onNodeExecuteStart(nodeContext);
                try {
                    suspendNodes.remove(currentNode.getId());
                    executeResult = currentNode.execute(this);
                } finally {
                    nodeContext.recordExecute(executeNode);
                    this.executeResult = executeResult;
                }
            } catch (Throwable error) {
                currentNode.setNodeStatus(ChainNodeStatus.ERROR);
                notifyNodeError(error, currentNode, executeResult);
                throw error;
            } finally {
                currentNode.setNodeStatusFinished();
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
            return;
        }

        List<ChainEdge> outwardEdges = currentNode.getOutwardEdges();

        if (CollectionUtil.hasItems(outwardEdges)) {
            List<Chain.ExecuteNode> nextExecuteNodes = new ArrayList<>(outwardEdges.size());
            for (ChainEdge chainEdge : outwardEdges) {
                ChainNode nextNode = getNodeById(chainEdge.getTarget());
                if (nextNode == null) {
                    continue;
                }
                EdgeCondition condition = chainEdge.getCondition();
                if (condition == null || condition.check(this, chainEdge)) {
                    nextExecuteNodes.add(new ExecuteNode(nextNode, currentNode, chainEdge.getId()));
                }
            }
            doExecuteNodes(nextExecuteNodes);
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

    private List<ChainNode> getStartNodes() {
        if (this.nodes == null || this.nodes.isEmpty()) {
            return null;
        }

        if (!this.suspendNodes.isEmpty()) {
            return suspendNodes.values().stream().collect(Collectors.toList());
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
                setStatusAndNotifyEvent(ChainStatus.RUNNING);
                runnable.run();
            } catch (ChainSuspendException cse) {
                notifySuspend();
                this.exception = cse;
            } catch (Exception e) {
                this.exception = e;
                setStatusAndNotifyEvent(ChainStatus.ERROR);
                notifyError(e);
            }

            this.phaser.arriveAndAwaitAdvance();

            if (status == ChainStatus.RUNNING) {
                setStatusAndNotifyEvent(ChainStatus.FINISHED_NORMAL);
            } else if (status == ChainStatus.ERROR) {
                setStatusAndNotifyEvent(ChainStatus.FINISHED_ABNORMAL);
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
        for (ChainErrorListener errorListener : chainErrorListeners) {
            errorListener.onError(error, this);
        }
        if (parent != null) parent.notifyError(error);
    }


    private void notifyNodeError(Throwable error, ChainNode node, Map<String, Object> executeResult) {
        for (NodeErrorListener errorListener : nodeErrorListeners) {
            errorListener.onError(error, node, executeResult, this);
        }
        if (parent != null) parent.notifyNodeError(error, node, executeResult);
    }


    public void stopNormal(String message) {
        this.message = message;
        setStatusAndNotifyEvent(ChainStatus.FINISHED_NORMAL);
    }


    public void stopError(String message) {
        this.message = message;
        setStatusAndNotifyEvent(ChainStatus.FINISHED_ABNORMAL);
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
            suspendNodes.putIfAbsent(node.getId(), node);
        } finally {
            setStatusAndNotifyEvent(ChainStatus.SUSPEND);
        }
    }

    public void resume(Map<String, Object> variables) {
        runInLifeCycle(variables,
            new ChainResumeEvent(this, variables),
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

    public void reset() {
        //node
        this.memory.clear();
        this.nodeStatus = ChainNodeStatus.READY;


        //chain
        this.status = ChainStatus.READY;
        this.executeResult = null;
        this.message = null;
        this.exception = null;
        this.nodeContexts.clear();


        if (this.suspendNodes != null) {
            this.suspendNodes.clear();
        }

        if (this.suspendForParameters != null) {
            this.suspendForParameters.clear();
        }

        this.asyncNodeExecutors = NamedThreadPools.newFixedThreadPool("chain-executor");
        this.phaser = new Phaser(1);
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
