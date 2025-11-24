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
package com.agentsflex.core.message;

import com.agentsflex.core.util.Copyable;
import com.alibaba.fastjson2.JSON;

import java.io.Serializable;
import java.util.Map;

public class ToolCall implements Serializable, Copyable<ToolCall> {

    private String id;
    private String name;
    private String argsString;

    public ToolCall() {
    }

    public ToolCall(String id, String name, String argsString) {
        this.id = id;
        this.name = name;
        this.argsString = argsString;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArgsString() {
        return argsString;
    }

    public void setArgsString(String argsString) {
        this.argsString = argsString;
    }

    public Map<String, Object> getArgsMap() {
        if (argsString == null || argsString.isEmpty()) {
            return null;
        }
        try {
            String jsonStr = argsString.trim();
            if (!jsonStr.startsWith("{")) jsonStr = "{" + jsonStr;
            if (!jsonStr.endsWith("}")) jsonStr = jsonStr + "}";
            return JSON.parseObject(jsonStr);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ToolCall{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", argsString='" + argsString + '\'' +
            '}';
    }

    /**
     * 创建并返回当前对象的副本。
     *
     * @return 一个新的、内容相同但内存独立的对象
     */
    @Override
    public ToolCall copy() {
        ToolCall copy = new ToolCall();
        copy.id = this.id;
        copy.name = this.name;
        copy.argsString = this.argsString;
        return copy;
    }
}
