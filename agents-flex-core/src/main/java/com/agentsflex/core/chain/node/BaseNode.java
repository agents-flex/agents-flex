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

import com.agentsflex.core.chain.InputParameter;
import com.agentsflex.core.chain.OutputKey;
import com.agentsflex.core.chain.RefType;
import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.ChainNode;
import com.agentsflex.core.util.StringUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseNode extends ChainNode {

    protected String description;
    protected List<InputParameter> inputParameters;
    protected List<OutputKey> outputKeys;


    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<InputParameter> getInputParameters() {
        return inputParameters;
    }

    public void setInputParameters(List<InputParameter> inputParameters) {
        this.inputParameters = inputParameters;
    }

    public void addInputParameter(InputParameter inputParameter) {
        if (inputParameters == null) {
            inputParameters = new java.util.ArrayList<>();
        }
        inputParameters.add(inputParameter);
    }

    public List<OutputKey> getOutputKeys() {
        return outputKeys;
    }

    public void setOutputKeys(List<OutputKey> outputKeys) {
        this.outputKeys = outputKeys;
    }

    public void addOutputKey(OutputKey outputKey) {
        if (outputKeys == null) {
            outputKeys = new java.util.ArrayList<>();
        }
        outputKeys.add(outputKey);
    }


    public Map<String, Object> getChainParameters(Chain chain, List<InputParameter> inputParameters) {
        Map<String, Object> variables = new HashMap<>();
        if (inputParameters != null) {
            for (InputParameter parameter : inputParameters) {
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
                variables.put(parameter.getName(), value);
            }
        }
        return variables;
    }

    public Map<String, Object> getParameters(Chain chain) {
        return getChainParameters(chain, this.inputParameters);
    }
}
