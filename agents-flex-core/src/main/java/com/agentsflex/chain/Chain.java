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
import com.agentsflex.chain.events.OnErrorEvent;
import com.agentsflex.memory.ContextMemory;
import com.agentsflex.memory.DefaultContextMemory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public abstract class Chain<Input, Output> implements Serializable {

    protected String id;

    protected ContextMemory context = new DefaultContextMemory();
    protected Map<String, List<ChainEventListener>> listeners = new HashMap<>();
    protected List<Invoker> invokers;

    protected Chain<?, ?> parent;
    protected Input input;
    protected Output output;
    protected Object lastResult;

    protected boolean stopFlag = false;

    public String getId() {
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

    public Map<String, List<ChainEventListener>> getListeners() {
        return listeners;
    }

    public void setListeners(Map<String, List<ChainEventListener>> listeners) {
        this.listeners = listeners;
    }

    public synchronized void registerListener(String name, ChainEventListener listener) {
        List<ChainEventListener> chainEventListeners = listeners.computeIfAbsent(name, k -> new ArrayList<>());
        chainEventListeners.add(listener);
    }

    public synchronized void removeListener(ChainEventListener listener) {
        for (List<ChainEventListener> list : listeners.values()) {
            list.removeIf(item -> item == listener);
        }
    }

    public synchronized void removeListener(String name, ChainEventListener listener) {
        List<ChainEventListener> list = listeners.get(name);
        if (list != null && !list.isEmpty()) {
            list.removeIf(item -> item == listener);
        }
    }

    public List<Invoker> getInvokers() {
        return invokers;
    }

    public void setInvokers(List<Invoker> invokers) {
        this.invokers = invokers;
    }

    public void addInvoker(Invoker invoker) {
        if (invokers == null) {
            this.invokers = new ArrayList<>();
        }
        if (invoker instanceof Chain) {
            ((Chain<?, ?>) invoker).parent = this;
        }
        invokers.add(invoker);
    }

    public void addInvoker(Agent<?> agent) {
        addInvoker(new AgentInvoker(agent));
    }

    public void addInvoker(Agent<?> agent, Condition condition) {
        addInvoker(new AgentInvoker(agent, condition));
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

    public Object getLastResult() {
        return lastResult;
    }

    public void setLastResult(Object lastResult) {
        this.lastResult = lastResult;
    }

    public Chain<?, ?> getParent() {
        return parent;
    }

    public void setParent(Chain<?, ?> parent) {
        this.parent = parent;
    }

    public void notify(ChainEvent event) {
        List<ChainEventListener> chainEventListeners = listeners.get(event.name());
        if (chainEventListeners != null) {
            chainEventListeners.forEach(chainEventListener -> chainEventListener.onEvent(event, Chain.this));
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

    public boolean isStop() {
        return stopFlag;
    }

    public Output execute(Input input) {
        this.input = input;
        this.lastResult = input;
        try {
            doExecuteAndSetOutput();
        } catch (Exception ex) {
            notify(new OnErrorEvent(this,ex));
        }
        return output;
    }

    protected abstract void doExecuteAndSetOutput();
}
