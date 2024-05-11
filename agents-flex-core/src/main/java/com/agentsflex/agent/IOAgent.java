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
package com.agentsflex.agent;

import com.agentsflex.chain.Chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * input and output agent
 */
public abstract class IOAgent extends Agent {
    private static final String PARAMETER_KEY = "default";

    public IOAgent() {
    }

    public IOAgent(Object id) {
        super(id);
    }

    public IOAgent(Object id, String name) {
        super(id, name);
    }


    @Override
    public List<Parameter> defineInputParameter() {
        List<Parameter> parameters = new ArrayList<>(1);
        parameters.add(new Parameter(PARAMETER_KEY, true));
        return parameters;
    }

    @Override
    public Output execute(Map<String, Object> variables, Chain chain) {
        Object value;
        if (variables == null || variables.isEmpty()) {
            value = null;
        } else if (variables.containsKey(PARAMETER_KEY)) {
            value = variables.get(PARAMETER_KEY);
        } else {
            String key = variables.keySet().iterator().next();
            value = variables.get(key);
        }
        return Output.ofValue(execute(value, chain));
    }

    public abstract Object execute(Object param, Chain chain);


}
