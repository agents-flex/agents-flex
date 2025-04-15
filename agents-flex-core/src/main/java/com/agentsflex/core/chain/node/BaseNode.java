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
package com.agentsflex.core.chain.node;

import com.agentsflex.core.chain.ChainNode;
import com.agentsflex.core.chain.Parameter;

import java.util.Collection;
import java.util.List;

public abstract class BaseNode extends ChainNode {

    protected List<Parameter> parameters;
    protected List<Parameter> outputDefs;


    public void setParameters(List<Parameter> parameters) {
        this.parameters = parameters;
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public void addInputParameter(Parameter parameter) {
        if (parameters == null) {
            parameters = new java.util.ArrayList<>();
        }
        parameters.add(parameter);
    }


    public List<Parameter> getOutputDefs() {
        return outputDefs;
    }

    public void setOutputDefs(List<Parameter> outputDefs) {
        this.outputDefs = outputDefs;
    }

    public void addOutputDef(Parameter parameter) {
        if (outputDefs == null) {
            outputDefs = new java.util.ArrayList<>();
        }
        outputDefs.add(parameter);
    }

    public void addOutputDefs(Collection<Parameter> parameters) {
        if (outputDefs == null) {
            outputDefs = new java.util.ArrayList<>();
        }
        outputDefs.addAll(parameters);
    }
}
