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
package com.agentsflex.core.chain.event;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.ChainNode;

import java.util.Map;

public class OnNodeEndEvent extends BaseChainEvent {

    private final ChainNode node;
    private final Map<String, Object> result;

    public OnNodeEndEvent(Chain chain, ChainNode node, Map<String, Object> result) {
        super(chain);
        this.node = node;
        this.result = result;
    }

    public ChainNode getNode() {
        return node;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    @Override
    public String toString() {
        return "OnNodeEndEvent{" +
            "chain=" + chain +
            ", node=" + node +
            ", result=" + result +
            '}';
    }
}