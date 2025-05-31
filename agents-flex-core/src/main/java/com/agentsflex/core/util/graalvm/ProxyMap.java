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
package com.agentsflex.core.util.graalvm;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ProxyMap implements ProxyObject {
    private final Map<Object, Object> map;
    private final Context context;

    public ProxyMap(Map<Object, Object> map, Context context) {
        this.map = map;
        this.context = context;
    }

    @Override
    public Object getMember(String key) {
        return JsInteropUtils.wrapJavaValueForJS(context, map.get(key)).as(Object.class);
    }

    @Override
    public boolean hasMember(String key) {
        return map.containsKey(key);
    }

    @Override
    public Set<String> getMemberKeys() {
        return map.keySet().stream()
            .map(Object::toString)
            .collect(Collectors.toSet());
    }

    @Override
    public void putMember(String key, Value value) {
        map.put(key, JsInteropUtils.unwrapJsValue(value));
    }
}
