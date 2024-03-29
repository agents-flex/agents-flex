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

import com.agentsflex.memory.ContextMemory;
import com.agentsflex.memory.DefaultContextMemory;

import java.io.Serializable;

public class Chain implements Serializable {
    protected String id;
    protected ContextMemory context = new DefaultContextMemory();
    protected Invoker[] invokers;
    protected int index = 0;

    public Chain(Invoker[] invokers) {
        this.invokers = invokers;
    }

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

    public Invoker[] getInvokers() {
        return invokers;
    }

    public void setInvokers(Invoker[] invokers) {
        this.invokers = invokers;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public void start() {
        doComplete();
    }

    public void stop() {

    }

    void doComplete() {
        invokers[index++].invoke();
    }
}
