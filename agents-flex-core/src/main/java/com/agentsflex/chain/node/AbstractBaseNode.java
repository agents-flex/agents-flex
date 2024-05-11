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
package com.agentsflex.chain.node;

import com.agentsflex.chain.ChainNode;

import java.util.UUID;

public abstract class AbstractBaseNode implements ChainNode {

    protected Object id;
    protected String name;
    protected boolean skip;

    public AbstractBaseNode() {
        this.id = UUID.randomUUID();
    }


    @Override
    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public AbstractBaseNode id(Object id) {
        this.id = id;
        return this;
    }


    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AbstractBaseNode name(String name) {
        this.name = name;
        return this;
    }

    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    @Override
    public boolean isSkip() {
        return skip;
    }

    public AbstractBaseNode skip() {
        this.skip = true;
        return this;
    }


}
