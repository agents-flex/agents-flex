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
package com.agentsflex.chain.event;

import com.agentsflex.chain.ChainEvent;
import com.agentsflex.chain.ChainNode;

public class OnNodeExecuteBeforeEvent implements ChainEvent {

    private ChainNode node;

    public OnNodeExecuteBeforeEvent(ChainNode node) {
        this.node = node;
    }

    public ChainNode getNode() {
        return node;
    }

    public void setNode(ChainNode node) {
        this.node = node;
    }
}
