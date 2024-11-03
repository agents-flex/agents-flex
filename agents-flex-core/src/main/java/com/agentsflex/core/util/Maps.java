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
package com.agentsflex.core.util;

import com.alibaba.fastjson.JSON;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class Maps extends HashMap<String, Object> {

    public static Maps of() {
        return new Maps();
    }

    public static Maps of(String key, Object value) {
        Maps maps = Maps.of();
        maps.put(key, value);
        return maps;
    }

    public static Maps ofNotNull(String key, Object value) {
        return new Maps().putIfNotNull(key, value);
    }

    public static Maps ofNotEmpty(String key, Object value) {
        return new Maps().putIfNotEmpty(key, value);
    }

    public static Maps ofNotEmpty(String key, Maps value) {
        return new Maps().putIfNotEmpty(key, value);
    }


    public Maps put(String key, Object value) {
        if (key.contains(".")) {
            String[] keys = key.split("\\.");
            Map<String, Object> currentMap = this;
            for (int i = 0; i < keys.length; i++) {
                String currentKey = keys[i].trim();
                if (currentKey.isEmpty()) {
                    continue;
                }
                if (i == keys.length - 1) {
                    currentMap.put(currentKey, value);
                } else {
                    //noinspection unchecked
                    currentMap = (Map<String, Object>) currentMap.computeIfAbsent(currentKey, k -> Maps.of());
                }
            }
        } else {
            super.put(key, value);
        }

        return this;
    }

    public Maps putOrDefault(String key, Object value, Object orDefault) {
        if (isNullOrEmpty(value)) {
            return this.put(key, orDefault);
        } else {
            return this.put(key, value);
        }
    }

    public Maps putIf(boolean condition, String key, Object value) {
        if (condition) put(key, value);
        return this;
    }

    public Maps putIf(Function<Maps, Boolean> func, String key, Object value) {
        if (func.apply(this)) put(key, value);
        return this;
    }

    public Maps putIfNotNull(String key, Object value) {
        if (value != null) put(key, value);
        return this;
    }

    public Maps putIfNotEmpty(String key, Object value) {
        if (!isNullOrEmpty(value)) {
            put(key, value);
        }
        return this;
    }

    private boolean isNullOrEmpty(Object value) {
        if (value == null) {
            return true;
        }

        if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
            return true;
        }

        if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) {
            return true;
        }

        if (value.getClass().isArray() && Array.getLength(value) == 0) {
            return true;
        }

        if (value instanceof String && ((String) value).trim().isEmpty()) {
            return true;
        }
        return false;
    }


    public Maps putIfContainsKey(String checkKey, String key, Object value) {
        if (this.containsKey(checkKey)) {
            this.put(key, value);
        }
        return this;
    }

    public Maps putIfNotContainsKey(String checkKey, String key, Object value) {
        if (!this.containsKey(checkKey)) {
            this.put(key, value);
        }
        return this;
    }

    public String toJSON() {
        return JSON.toJSONString(this);
    }

}
