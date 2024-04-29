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
package com.agentsflex.agent;

import java.util.HashMap;
import java.util.Map;

public class AgentOutput extends HashMap<String, Object> {

    private static final String DEFAULT_VALUE_KEY = "value__";

    public AgentOutput(int initialCapacity, float loadFactor) {
        super(initialCapacity, loadFactor);
    }

    public AgentOutput(int initialCapacity) {
        super(initialCapacity);
    }

    public AgentOutput() {
    }

    public AgentOutput(Map<String, ?> m) {
        super(m);
    }

    public Object getValue() {
        return get(DEFAULT_VALUE_KEY);
    }


    public AgentOutput set(String key,Object value){
        this.put(key,value);
        return this;
    }

    public static AgentOutput ofValue(Object value) {
        AgentOutput output = new AgentOutput();
        output.put(DEFAULT_VALUE_KEY, value);
        return output;
    }
}
