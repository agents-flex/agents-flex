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
import com.agentsflex.core.chain.ChainEvent;
import com.agentsflex.core.chain.ChainStatus;

public class OnStatusChangeEvent implements ChainEvent {

    private Chain chain;
    private ChainStatus status;
    private ChainStatus before;

    public OnStatusChangeEvent(Chain chain,ChainStatus status, ChainStatus before) {
        this.status = status;
        this.before = before;
    }

    public Chain getChain() {
        return chain;
    }

    public void setChain(Chain chain) {
        this.chain = chain;
    }

    public ChainStatus getStatus() {
        return status;
    }

    public void setStatus(ChainStatus status) {
        this.status = status;
    }

    public ChainStatus getBefore() {
        return before;
    }

    public void setBefore(ChainStatus before) {
        this.before = before;
    }

    @Override
    public String toString() {
        return "OnStatusChangeEvent{" +
            "chain=" + chain +
            ", status=" + status +
            ", before=" + before +
            '}';
    }
}
