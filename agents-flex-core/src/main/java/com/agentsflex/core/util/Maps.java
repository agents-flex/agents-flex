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

public class Maps {

    public static Builder of() {
        return new Builder();
    }

    public static Builder of(String key, Builder value) {
        return of(key, value.build());
    }

    public static Builder of(String key, Object value) {
        return new Builder().put(key, value);
    }

    public static Builder ofNotNull(String key, Object value) {
        return new Builder().putIfNotNull(key, value);
    }

    public static Builder ofNotEmpty(String key, Object value) {
        return new Builder().putIfNotEmpty(key, value);
    }

    public static Builder ofNotEmpty(String key, Builder value) {
        return new Builder().putIfNotEmpty(key, value);
    }

    public static class Builder {
        private Map<String, Object> map = new HashMap<>();

        public Builder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        public Builder putOrDefault(String key, Object value, Object orDefault) {
            if (isNullOrEmpty(value)) {
                return this.put(key, orDefault);
            } else {
                return this.put(key, value);
            }
        }

        public Builder put(String key, Builder value) {
            map.put(key, value.build());
            return this;
        }

        public Builder putIf(boolean condition, String key, Builder value) {
            if (condition) put(key, value);
            return this;
        }

        public Builder putIf(boolean condition, String key, Object value) {
            if (condition) put(key, value);
            return this;
        }

        public Builder putIf(Function<Map<String, Object>, Boolean> func, String key, Object value) {
            if (func.apply(map)) put(key, value);
            return this;
        }

        public Builder putIfNotNull(String key, Object value) {
            if (value != null) put(key, value);
            return this;
        }

        public Builder putIfNotEmpty(String key, Builder value) {
            Map<String, Object> map = value.build();
            if (map != null && !map.isEmpty()) put(key, value);
            return this;
        }

        public Builder putIfNotEmpty(String key, Object value) {
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


        public Builder putIfContainsKey(String checkKey, String key, Object value) {
            if (map.containsKey(checkKey)) {
                this.put(key, value);
            }
            return this;
        }

        public Builder putIfContainsKey(String checkKey, String key, Builder value) {
            if (map.containsKey(checkKey)) {
                this.put(key, value);
            }
            return this;
        }

        public Builder putIfNotContainsKey(String checkKey, String key, Object value) {
            if (!map.containsKey(checkKey)) {
                this.put(key, value);
            }
            return this;
        }

        public Builder putIfNotContainsKey(String checkKey, String key, Builder value) {
            if (!map.containsKey(checkKey)) {
                this.put(key, value);
            }
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }

        public Object get(String key) {
            return map.get(key);
        }

        public Map getAsMap(String key) {
            return (Map) map.get(key);
        }

        public String toJSON() {
            return JSON.toJSONString(map);
        }
    }

}
