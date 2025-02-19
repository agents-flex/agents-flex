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

import com.agentsflex.core.chain.*;
import com.agentsflex.core.util.StringUtil;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseNode extends ChainNode {

    protected String description;
    protected List<Parameter> parameters;
    protected List<Parameter> outputDefs;


    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public void setParameters(List<Parameter> parameters) {
        this.parameters = parameters;
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

    public void addOutputDef(Parameter parameter){
        if (outputDefs == null) {
            outputDefs = new java.util.ArrayList<>();
        }
        outputDefs.add(parameter);
    }

    public void addOutputDefs(Collection<Parameter> parameters){
        if (outputDefs == null) {
            outputDefs = new java.util.ArrayList<>();
        }
        outputDefs.addAll(parameters);
    }



    public Map<String, Object> getChainParameters(Chain chain, List<Parameter> parameters) {
        Map<String, Object> variables = new HashMap<>();
        if (parameters != null) {
            for (Parameter parameter : parameters) {
                RefType refType = parameter.getRefType();
                Object value;
                if (refType == RefType.INPUT) {
                    value = parameter.getRef();
                } else if (refType == RefType.REF) {
                    value = chain.get(parameter.getRef());
                } else {
                    value = chain.get(parameter.getName());
                }
                if (parameter.isRequired() &&
                    (value == null || (value instanceof String && StringUtil.noText((String) value)))) {
                    chain.stopError(this.getName() + " Missing required parameter:" + parameter.getName());
                }
                if (value == null || value instanceof String) {
                    value = value == null ? "" : ((String) value).trim();
                    if (parameter.getDataType() == DataType.Boolean) {
                        value = "true".equalsIgnoreCase((String) value) || "1".equalsIgnoreCase((String) value);
                    } else if (parameter.getDataType() == DataType.Number) {
                        value = Long.parseLong((String) value);
                    }
                }

                variables.put(parameter.getName(), value);
            }
        }
        return variables;
    }

    public Map<String, Object> getParameters(Chain chain) {
        return getChainParameters(chain, this.parameters);
    }
}
