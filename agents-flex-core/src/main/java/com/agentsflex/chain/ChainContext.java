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
package com.agentsflex.chain;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class ChainContext implements Serializable {

    protected Map<String, Object> contextMap;

    public Object get(String key) {
        return contextMap != null ? contextMap.get(key) : null;
    }

    public void put(String key, Object value) {
        if (contextMap == null) {
            contextMap = new HashMap<>();
        }
        contextMap.put(key, value);
    }

    public void putAll(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        if (contextMap == null) {
            contextMap = new HashMap<>();
        }
        contextMap.putAll(context);
    }

    public Map<String, Object> getContextMap() {
        return contextMap;
    }

    public void setContextMap(Map<String, Object> contextMap) {
        this.contextMap = contextMap;
    }


}
