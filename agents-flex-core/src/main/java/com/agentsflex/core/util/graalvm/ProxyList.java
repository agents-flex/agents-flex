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
import org.graalvm.polyglot.proxy.ProxyArray;

import java.util.List;

public class ProxyList implements ProxyArray {
    private final List<Object> list;
    private final Context context;

    public ProxyList(List<Object> list, Context context) {
        this.list = list;
        this.context = context;
    }


    @Override
    public Object get(long index) {
        return JsInteropUtils.wrapJavaValueForJS(context, list.get((int)index)).as(Object.class);
    }

    @Override
    public boolean remove(long index) {
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            return true;
        }
        return false;
    }

    @Override
    public void set(long index, Value value) {
        if (index >= 0 && index < list.size()) {
            list.set((int)index, JsInteropUtils.unwrapJsValue(value));
        }
    }


    @Override
    public long getSize() {
        return list.size();
    }
}
