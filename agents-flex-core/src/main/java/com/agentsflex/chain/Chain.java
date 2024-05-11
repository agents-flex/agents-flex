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
package com.agentsflex.chain;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.Parameter;
import com.agentsflex.chain.event.OnErrorEvent;
import com.agentsflex.chain.event.OnFinishedEvent;
import com.agentsflex.chain.event.OnStatusChangeEvent;
import com.agentsflex.chain.node.AgentNode;
import com.agentsflex.memory.ContextMemory;
import com.agentsflex.memory.DefaultContextMemory;

import java.io.Serializable;
import java.util.*;


public abstract class Chain implements Serializable {
    private Object id;
    private ContextMemory memory = new DefaultContextMemory();
    private Map<Class<?>, List<ChainEventListener>> eventListeners = new HashMap<>(0);
    private List<ChainInputListener> inputListeners = new ArrayList<>();
    private List<ChainOutputListener> outputListeners = new ArrayList<>();
    private List<ChainNode> nodes;
    private Chain parent;
    private List<Chain> children;
    private ChainStatus status = ChainStatus.READY;

    //理论上是线程安全的，所有有多线程写入的情况，但是只有全部写入完成后才会去通知监听器
    private List<Parameter> waitInputParameters = new ArrayList<>();


    public Chain() {
        this.id = UUID.randomUUID();
    }

    public Object getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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


    public List<ChainInputListener> getInputListeners() {
        return inputListeners;
    }

    public void setInputListeners(List<ChainInputListener> inputListeners) {
        this.inputListeners = inputListeners;
    }

    public void registerInputListener(ChainInputListener inputListener) {
        if (this.inputListeners == null) {
            this.inputListeners = new ArrayList<>();
        }
        this.inputListeners.add(inputListener);
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
        if (chainNode instanceof Chain) {
            ((Chain) chainNode).parent = this;
        }
        if (chainNode instanceof ChainEventListener) {
            registerEventListener((ChainEventListener) chainNode);
        }

        nodes.add(chainNode);
    }

    public void addNode(Agent agent) {
        addNode(new AgentNode(agent));
    }


    public Chain getParent() {
        return parent;
    }

    public void setParent(Chain parent) {
        this.parent = parent;
    }


    public void setId(Object id) {
        this.id = id;
    }

    public ContextMemory getMemory() {
        return memory;
    }

    public void setMemory(ContextMemory memory) {
        this.memory = memory;
    }


    public List<Chain> getChildren() {
        return children;
    }

    public void setChildren(List<Chain> children) {
        this.children = children;
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

    public List<Parameter> getWaitInputParameters() {
        return waitInputParameters;
    }

    public void setWaitInputParameters(List<Parameter> waitInputParameters) {
        this.waitInputParameters = waitInputParameters;
    }

    public void notifyEvent(ChainEvent event) {
        for (Map.Entry<Class<?>, List<ChainEventListener>> entry : eventListeners.entrySet()) {
            if (entry.getKey().isInstance(event)) {
                for (ChainEventListener chainEventListener : entry.getValue()) {
                    chainEventListener.onEvent(event, this);
                }
            }
        }
        if (parent != null) {
            parent.notifyEvent(event);
        }
    }

    public Object get(String key) {
        return this.memory.get(key);
    }

    public Object getGlobal(String key) {
        Object object = this.memory.get(key);
        if (object != null) {
            return object;
        }

        if (parent != null) {
            return parent.getGlobal(key);
        }
        return null;
    }

    public void execute(Map<String, Object> variables) {
        runInLifeCycle(variables, this::executeInternal);
    }


    protected abstract void executeInternal();

    public boolean resume(Map<String, Object> variables) {
        if (status != ChainStatus.PAUSE_FOR_INPUT &&
            status != ChainStatus.PAUSE_FOR_WAKE_UP) {
            return false;
        }

        if (variables == null || variables.isEmpty()) {
            return false;
        }

        for (Parameter waitInputParameter : this.waitInputParameters) {
            if (variables.get(waitInputParameter.getName()) == null) {
                return false;
            }
        }

        waitInputParameters.clear();
        runInLifeCycle(variables, () -> resumeInternal(variables));
        return true;
    }

    protected void runInLifeCycle(Map<String, Object> variables, Runnable runnable) {
        if (variables != null) {
            this.memory.putAll(variables);
        }
        try {
            setStatus(ChainStatus.START);
            runnable.run();
        } catch (Exception e) {
            setStatus(ChainStatus.ERROR);
            notifyEvent(new OnErrorEvent(e));
        } finally {
            if (!waitInputParameters.isEmpty()) {
                notifyInput(waitInputParameters);
            }
        }

        try {
            if (status == ChainStatus.START) {
                setStatus(ChainStatus.FINISHED_NORMAL);
            } else if (status == ChainStatus.ERROR) {
                setStatus(ChainStatus.FINISHED_ABNORMAL);
            }
        } finally {
            notifyEvent(new OnFinishedEvent());
        }
    }


    protected abstract void resumeInternal(Map<String, Object> variables);


    public void waitInput(List<Parameter> parameters, AgentNode agent) {
        setStatus(ChainStatus.PAUSE_FOR_INPUT);
        this.waitInputParameters.addAll(parameters);
    }

    private void notifyInput(List<Parameter> parameters) {
        for (ChainInputListener inputListener : inputListeners) {
            inputListener.onInput(this, parameters);
        }
    }

    public void stop() {
        setStatus(ChainStatus.FINISHED_NORMAL);
        if (parent != null) {
            parent.stop();
        }
    }

    @Override
    public String toString() {
        return "Chain{" +
            "id=" + id +
            '}';
    }


}
