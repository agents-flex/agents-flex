/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.chain;

import com.agentsflex.agent.Agent;
import com.agentsflex.chain.event.OnErrorEvent;
import com.agentsflex.chain.event.OnNodeExecuteAfterEvent;
import com.agentsflex.chain.event.OnNodeExecuteBeforeEvent;
import com.agentsflex.chain.node.AgentNode;
import com.agentsflex.chain.result.SingleNodeResult;
import com.agentsflex.memory.ContextMemory;
import com.agentsflex.memory.DefaultContextMemory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public abstract class Chain<Input, Output> implements Serializable {

    protected Object id;

    protected ContextMemory context = new DefaultContextMemory();
    protected Map<Class<?>, List<ChainEventListener>> listeners = new HashMap<>();
    protected List<ChainNode> chainNodes;

    protected Chain<?, ?> parent;

    protected Input input;
    protected Output output;
    protected NodeResult<?> lastResult;
    protected boolean stopFlag = false;

    public Object getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ContextMemory getContext() {
        return context;
    }

    public void setContext(ContextMemory context) {
        this.context = context;
    }

    public Map<Class<?>, List<ChainEventListener>> getListeners() {
        return listeners;
    }

    public void setListeners(Map<Class<?>, List<ChainEventListener>> listeners) {
        this.listeners = listeners;
    }

    public synchronized void registerListener(Class<?> eventClass, ChainEventListener listener) {
        List<ChainEventListener> chainEventListeners = listeners.computeIfAbsent(eventClass, k -> new ArrayList<>());
        chainEventListeners.add(listener);
    }

    public synchronized void removeListener(ChainEventListener listener) {
        for (List<ChainEventListener> list : listeners.values()) {
            list.removeIf(item -> item == listener);
        }
    }

    public synchronized void removeListener(Class<?> eventClass, ChainEventListener listener) {
        List<ChainEventListener> list = listeners.get(eventClass);
        if (list != null && !list.isEmpty()) {
            list.removeIf(item -> item == listener);
        }
    }

    public List<ChainNode> getInvokers() {
        return chainNodes;
    }

    public void setInvokers(List<ChainNode> chainNodes) {
        this.chainNodes = chainNodes;
    }

    public void addInvoker(ChainNode chainNode) {
        if (chainNodes == null) {
            this.chainNodes = new ArrayList<>();
        }
        if (chainNode instanceof Chain) {
            ((Chain<?, ?>) chainNode).parent = this;
        }
        chainNodes.add(chainNode);
    }

    public void addInvoker(Agent<?> agent) {
        addInvoker(new AgentNode(agent));
    }

    public Input getInput() {
        return input;
    }

    public void setInput(Input input) {
        this.input = input;
    }

    public Output getOutput() {
        return output;
    }

    public void setOutput(Output output) {
        this.output = output;
    }

    public NodeResult<?> getLastResult() {
        return lastResult;
    }

    public void setLastResult(NodeResult<?> lastResult) {
        this.lastResult = lastResult;
    }

    public Chain<?, ?> getParent() {
        return parent;
    }

    public void setParent(Chain<?, ?> parent) {
        this.parent = parent;
    }

    public void notify(ChainEvent event) {
        List<ChainEventListener> chainEventListeners = listeners.get(event.getClass());
        if (chainEventListeners != null) {
            chainEventListeners.forEach(chainEventListener -> chainEventListener.onEvent(event, Chain.this));
        }
        if (parent != null) {
            parent.notify(event);
        }
    }

    public void stop() {
        stopFlag = true;
    }

    public void stop(boolean stop) {
        stopFlag = stop;
    }

    public void stopAndOutput(Output output) {
        stopFlag = true;
        this.output = output;
    }

    public void stopGlobal() {
        this.stop();
        if (parent != null) {
            parent.stopGlobal();
        }
    }

    public boolean isStop() {
        return stopFlag;
    }

    public Object get(String key) {
        return this.context.get(key);
    }

    public Object getGlobal(String key) {
        Object object = this.context.get(key);
        if (object != null) {
            return object;
        }

        if (parent != null) {
            return parent.getGlobal(key);
        }
        return null;
    }

    public Output execute(Input input) {
        this.input = input;
        this.lastResult = new SingleNodeResult(input);
        try {
            executeInternal();
        } catch (Exception e) {
            notify(new OnErrorEvent(e));
        }

        if (output != null) {
            return output;
        }

        if (lastResult == null) {
            return null;
        }

        if (lastResult instanceof SingleNodeResult) {
            //noinspection unchecked
            return (Output) lastResult.getValue();
        }

        throw new IllegalStateException("Can not give the output for multi result.");
    }


    public NodeResult<?> runNode(ChainNode node) {
        try {
            notify(new OnNodeExecuteBeforeEvent(node, lastResult));
            return node.execute(this.lastResult, this);
        } finally {
            notify(new OnNodeExecuteAfterEvent(node, lastResult));
        }
    }

    protected abstract void executeInternal();
}
