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
package com.agentsflex.core.agent.react;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.List;

/**
 * ReAct Agent 工具函数辅助类
 */
public class Util {

    /**
     * 生成带缩进的空格字符串
     */
    public static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  "); // 2 spaces per level
        }
        return sb.toString();
    }

    /**
     * 基于工具列表生成结构化、LLM 友好的工具描述文本
     */
    public static String buildToolsDescription(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("### 工具名称: ").append(tool.getName()).append("\n");
            sb.append("**描述**: ").append(tool.getDescription()).append("\n");
            sb.append("**调用参数格式 (JSON 对象)**:\n");
            sb.append("```json\n");

            sb.append("{\n");
            Parameter[] rootParams = tool.getParameters();
            for (int i = 0; i < rootParams.length; i++) {
                appendParameter(sb, rootParams[i], 1);
                if (i < rootParams.length - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("}\n");
            sb.append("```\n\n");
        }
        return sb.toString();
    }

    /**
     * 递归追加参数描述（支持 object 和 array）
     */
    private static void appendParameter(StringBuilder sb, Parameter param, int depth) {
        String currentIndent = indent(depth);
        String typeLabel = getTypeLabel(param);

        // 构建注释信息
        StringBuilder comment = new StringBuilder();
        if (param.isRequired()) {
            comment.append(" (必填)");
        } else {
            comment.append(" (可选)");
        }

        if (param.getDescription() != null && !param.getDescription().trim().isEmpty()) {
            comment.append(" - ").append(param.getDescription().trim());
        }

        if (param.getEnums() != null && param.getEnums().length > 0) {
            comment.append(" [可选值: ").append(String.join(", ", param.getEnums())).append("]");
        }

        String paramName = param.getName() != null ? param.getName() : "item";

        // 判断是否为数组类型
        boolean isArray = isArrayType(param.getType());

        if (isArray && param.getChildren() != null && !param.getChildren().isEmpty()) {
            // 数组元素为对象：描述其结构
            sb.append(currentIndent).append("\"").append(paramName).append("\": [");
            sb.append(comment).append("\n");

            String innerIndent = indent(depth + 1);
            sb.append(innerIndent).append("{\n");

            List<Parameter> elementFields = param.getChildren();
            for (int i = 0; i < elementFields.size(); i++) {
                appendParameter(sb, elementFields.get(i), depth + 2);
                if (i < elementFields.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(innerIndent).append("}\n");
            sb.append(currentIndent).append("]");

        } else if (isArray) {
            // 简单类型数组
            sb.append(currentIndent).append("\"").append(paramName).append("\": [ \"<")
                .append(typeLabel).append(">")
                .append(comment)
                .append("\" ]");

        } else if (param.getChildren() != null && !param.getChildren().isEmpty()) {
            // 嵌套对象
            sb.append(currentIndent).append("\"").append(paramName).append("\": {");
            sb.append(comment).append("\n");

            List<Parameter> children = param.getChildren();
            for (int i = 0; i < children.size(); i++) {
                appendParameter(sb, children.get(i), depth + 1);
                if (i < children.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(currentIndent).append("}");

        } else {
            // 叶子字段（简单类型）
            sb.append(currentIndent).append("\"").append(paramName).append("\": \"<")
                .append(typeLabel).append(">")
                .append(comment)
                .append("\"");
        }
    }

    /**
     * 判断类型是否为数组
     */
    private static boolean isArrayType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase();
        return "array".equals(lower) || "list".equals(lower);
    }

    /**
     * 获取标准化的类型标签
     */
    private static String getTypeLabel(Parameter param) {
        String type = param.getType();
        if (type == null) return "string";

        // 若有子字段，则视为 object
        if (param.getChildren() != null && !param.getChildren().isEmpty()) {
            return "object";
        }

        String lower = type.toLowerCase();
        if ("string".equals(lower) || "str".equals(lower)) {
            return "string";
        } else if ("integer".equals(lower) || "int".equals(lower)) {
            return "integer";
        } else if ("number".equals(lower) || "float".equals(lower) || "double".equals(lower)) {
            return "number";
        } else if ("boolean".equals(lower) || "bool".equals(lower)) {
            return "boolean";
        } else if ("array".equals(lower) || "list".equals(lower)) {
            return "array";
        } else {
            return type; // 保留自定义类型名，如 date, uri 等
        }
    }
}
