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

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.node.CodeNode;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.util.HashMap;
import java.util.Map;

public class GroovyExecNode extends CodeNode {

    @Override
    protected Map<String, Object> executeCode(String code, Chain chain) {
        Binding binding = new Binding();

        Map<String, Object> parameters = getParameters(chain);
        if (parameters != null) {
            parameters.forEach(binding::setVariable);
        }

        Map<String, Object> result = new HashMap<>();
        binding.setVariable("_result", result);
        binding.setVariable("_chain", chain);

        GroovyShell shell = new GroovyShell(binding);
        shell.evaluate(code);
        return result;
    }

    @Override
    public String toString() {
        return "GroovyExecNode{" +
            "inwardEdges=" + inwardEdges +
            ", code='" + code + '\'' +
            ", description='" + description + '\'' +
            ", parameters=" + parameters +
            ", outputDefs=" + outputDefs +
            ", id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", async=" + async +
            ", outwardEdges=" + outwardEdges +
            ", condition=" + condition +
            ", memory=" + memory +
            ", nodeStatus=" + nodeStatus +
            '}';
    }
}
