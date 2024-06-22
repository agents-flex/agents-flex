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
package com.agentsflex.core.agent;

import java.util.HashMap;
import java.util.Map;

public class Output extends HashMap<String, Object> {

    public static final String DEFAULT_VALUE_KEY = "default";

    public Output(int initialCapacity, float loadFactor) {
        super(initialCapacity, loadFactor);
    }

    public Output(int initialCapacity) {
        super(initialCapacity);
    }

    public Output() {
    }

    public Output(Map<String, ?> m) {
        super(m);
    }

    public Object getValue() {
        return get(DEFAULT_VALUE_KEY);
    }


    public Output set(String key, Object value) {
        this.put(key, value);
        return this;
    }

    public static Output of(OutputKey key, Object value) {
        return of(key.getKey(), value);
    }

    public static Output of(String key, Object value) {
        Output output = new Output();
        output.put(key, value);
        return output;
    }

    public static Output ofDefault(Object value) {
        return of(DEFAULT_VALUE_KEY, value);
    }
}
