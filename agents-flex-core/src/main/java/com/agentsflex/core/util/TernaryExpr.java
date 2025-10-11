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

import java.util.Collection;

public class TernaryExpr {
    final String condition;
    final String trueExpr;
    final String falseExpr;

    public TernaryExpr(String condition, String trueExpr, String falseExpr) {
        this.condition = condition.trim();
        this.trueExpr = trueExpr.trim();
        this.falseExpr = falseExpr.trim();
    }

    public static TernaryExpr of(String key) {
        // 简单解析：找最外层的 '?' 和对应的 ':'
        // 注意：不支持嵌套三目（如 a ? b ? c : d : e），如需支持需用栈解析
        int questionIndex = -1;
        int colonIndex = -1;

        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '?') {
                if (questionIndex == -1) {
                    questionIndex = i;
                }
            } else if (c == ':') {
                if (questionIndex != -1) {
                    colonIndex = i;
                    break; // 找到第一个匹配的 :
                }
            }
            // 可扩展：处理括号、引号等（当前简化）
        }

        if (questionIndex > 0 && colonIndex > questionIndex + 1) {
            String condition = key.substring(0, questionIndex);
            String truePart = key.substring(questionIndex + 1, colonIndex);
            String falsePart = key.substring(colonIndex + 1);
            return new TernaryExpr(condition, truePart, falsePart);
        }
        return null;
    }

    public static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return !((String) value).isEmpty() && !"0".equals(value) && !"false".equalsIgnoreCase((String) value);
        if (value instanceof Collection) return !((Collection<?>) value).isEmpty();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0;
        return true; // 其他对象视为 true
    }

    public String getCondition() {
        return condition;
    }

    public String getTrueExpr() {
        return trueExpr;
    }

    public String getFalseExpr() {
        return falseExpr;
    }
}
