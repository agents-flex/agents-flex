/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.prompt;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.agentsflex.core.util.Metadata;

import java.util.*;


public abstract class Prompt extends Metadata {

    public abstract List<Message> getMessages();

    private List<Tool> tools;
    private String toolChoice;

    public void addTool(Tool tool) {
        if (this.tools == null)
            this.tools = new java.util.ArrayList<>();
        this.tools.add(tool);
    }

    public void addTools(Collection<? extends Tool> functions) {
        if (this.tools == null) {
            this.tools = new java.util.ArrayList<>();
        }
        if (functions != null) {
            this.tools.addAll(functions);
        }
    }

    public void addToolsFromClass(Class<?> funcClass, String... methodNames) {
        if (this.tools == null)
            this.tools = new java.util.ArrayList<>();
        this.tools.addAll(ToolScanner.scan(funcClass, methodNames));
    }

    public void addToolsFromObject(Object funcObject, String... methodNames) {
        if (this.tools == null)
            this.tools = new java.util.ArrayList<>();
        this.tools.addAll(ToolScanner.scan(funcObject, methodNames));
    }

    public List<Tool> getTools() {
        return tools;
    }

    public Map<String, Tool> getToolsMap() {
        if (tools == null) {
            return Collections.emptyMap();
        }
        Map<String, Tool> map = new HashMap<>(tools.size());
        for (Tool tool : tools) {
            map.put(tool.getName(), tool);
        }
        return map;
    }

    public void setTools(List<? extends Tool> tools) {
        if (tools == null) {
            this.tools = null;
        } else {
            this.tools = new ArrayList<>(tools);
        }
    }

    public String getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
    }
}
