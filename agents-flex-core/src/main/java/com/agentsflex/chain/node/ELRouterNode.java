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

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.ChainNode;

import java.util.List;

public class ELRouterNode extends RouterNode {
    private String elContent;
    private ELEngine elEngine;

    public String getElContent() {
        return elContent;
    }

    public void setElContent(String elContent) {
        this.elContent = elContent;
    }

    public ELEngine getElEngine() {
        return elEngine;
    }

    public void setElEngine(ELEngine elEngine) {
        this.elEngine = elEngine;
    }

    public ELRouterNode(List<ChainNode> nodes) {
        super(nodes);
    }

    @Override
    protected String route(Chain chain) {
        return elEngine.run(elContent, chain);
    }

    @Override
    public String toString() {
        return "ELRouterNode{" +
            "elContent='" + elContent + '\'' +
            ", elEngine=" + elEngine +
            ", id=" + id +
            ", skip=" + skip +
            '}';
    }
}
