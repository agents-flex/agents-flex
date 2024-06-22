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

import com.agentsflex.core.chain.ChainEvent;
import com.agentsflex.core.chain.ChainNode;

import java.util.Map;

public class OnNodeFinishedEvent implements ChainEvent {

    private ChainNode node;
    private Map<String, Object> result;


    public OnNodeFinishedEvent(ChainNode node, Map<String, Object> result) {
        this.node = node;
        this.result = result;
    }

    public ChainNode getNode() {
        return node;
    }

    public void setNode(ChainNode node) {
        this.node = node;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "OnNodeFinishedEvent{" +
            "node=" + node +
            ", result=" + result +
            '}';
    }
}
