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
import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class GroovyRouterNode extends RouterNode {

    private String express;

    public GroovyRouterNode() {

    }

    public GroovyRouterNode(String express) {
        this.express = express;
    }

    public GroovyRouterNode(String express, ChainNode... nodes) {
        this.express = express;
        this.setNodes(Arrays.asList(nodes));
    }

    public GroovyRouterNode(List<ChainNode> nodes, String express) {
        super(nodes);
        this.express = express;
    }

    @Override
    protected String route(Chain chain) {
        Binding binding = new Binding();
        Map<String, Object> all = chain.getMemory().getAll();
        if (all != null) {
            all.forEach(binding::setVariable);
        }

        binding.setVariable("chain", chain);
        GroovyShell shell = new GroovyShell(binding);
        Object value = shell.evaluate(express);
        return value == null ? null : value.toString();
    }

    public String getExpress() {
        return express;
    }

    public void setExpress(String express) {
        this.express = express;
    }
}
