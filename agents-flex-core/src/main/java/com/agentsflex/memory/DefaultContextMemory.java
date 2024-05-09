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
package com.agentsflex.memory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultContextMemory implements ContextMemory {
    @Override
    public Object id() {
        return UUID.randomUUID().toString();
    }

    protected Map<String, Object> context = new ConcurrentHashMap<>();

    @Override
    public Object get(String key) {
        return context.get(key);
    }

    @Override
    public Map<String, Object> getAll() {
        return context;
    }

    @Override
    public void put(String key, Object value) {
        context.put(key, value);
    }

    @Override
    public void putAll(Map<String, Object> context) {
        this.context.putAll(context);
    }

    @Override
    public void remove(String key) {
        this.context.remove(key);
    }

    @Override
    public void clear() {
        context.clear();
    }


}
