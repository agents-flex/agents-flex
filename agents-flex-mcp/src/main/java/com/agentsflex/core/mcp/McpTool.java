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
package com.agentsflex.core.mcp;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class McpTool implements Tool {

    final McpSyncClient mcpClient;
    final McpSchema.Tool mcpOriginalTool;

    public McpTool(McpSyncClient mcpClient, McpSchema.Tool mcpOriginalTool) {
        this.mcpClient = mcpClient;
        this.mcpOriginalTool = mcpOriginalTool;
    }

    @Override
    public String getName() {
        return mcpOriginalTool.name();
    }

    @Override
    public String getDescription() {
        return mcpOriginalTool.description();
    }

    @Override
    public Parameter[] getParameters() {
        McpSchema.JsonSchema inputSchema = mcpOriginalTool.inputSchema();
        if (inputSchema == null) {
            return new Parameter[0];
        }

        Map<String, Object> properties = inputSchema.properties();
        if (properties == null || properties.isEmpty()) {
            return new Parameter[0];
        }

        List<String> required = inputSchema.required();
        if (required == null) required = Collections.emptyList();

        Parameter[] parameters = new Parameter[properties.size()];
        int i = 0;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Parameter parameter = new Parameter();
            parameter.setName(entry.getKey());

            //"type" -> "number"
            //"minimum" -> {Integer@3634} 1
            //"maximum" -> {Integer@3636} 10
            //"default" -> {Integer@3638} 3
            //"description" -> "Number of resource links to return (1-10)"
            //"enum" -> {ArrayList@3858}  size = 3
            // key = "enum"
            // value = {ArrayList@3858}  size = 3
            //  0 = "error"
            //  1 = "success"
            //  2 = "debug"
            //"additionalProperties" -> {LinkedHashMap@3759}  size = 3
            // key = "additionalProperties"
            // value = {LinkedHashMap@3759}  size = 3
            //  "type" -> "string"
            //  "format" -> "uri"
            //  "description" -> "URL of the file to include in the zip"
            @SuppressWarnings("unchecked") Map<String, Object> entryValue = (Map<String, Object>) entry.getValue();

            parameter.setType((String) entryValue.get("type"));
            parameter.setDescription((String) entryValue.get("description"));
            parameter.setDefaultValue(entryValue.get("default"));

            if (required.contains(entry.getKey())) {
                parameter.setRequired(true);
            }

            Object anEnum = entryValue.get("enum");
            if (anEnum instanceof Collection<?>) {
                parameter.setEnums(((Collection<?>) anEnum).toArray(new String[0]));
            }

            parameters[i++] = parameter;
        }

        return parameters;
    }

    @Override
    public Object invoke(Map<String, Object> argsMap) {
        McpSchema.CallToolResult callToolResult = mcpClient.callTool(new McpSchema.CallToolRequest(mcpOriginalTool.name(), argsMap));
        List<McpSchema.Content> content = callToolResult.content();
        if (content == null || content.isEmpty()) {
            return null;
        }

        if (content.size() == 1 && content.get(0) instanceof McpSchema.TextContent) {
            return ((McpSchema.TextContent) content.get(0)).text();
        }

        return content;
    }
}
