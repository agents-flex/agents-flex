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
package com.agentsflex.prompt.template;

import com.agentsflex.prompt.SimplePrompt;

import java.util.*;

public class SimplePromptTemplate implements PromptTemplate<SimplePrompt> {

    private final Set<String> keys = new HashSet<>();

    private final List<String> parts = new ArrayList<String>() {
        @Override
        public boolean add(String string) {
            if (string.charAt(0) == '{' && string.length() > 2 && string.charAt(string.length() - 1) == '}') {
                keys.add(string.substring(1, string.length() - 1));
            }
            return super.add(string);
        }
    };

    public SimplePromptTemplate(String template) {
        boolean isCurrentInKeyword = false;
        StringBuilder keyword = null;
        StringBuilder content = null;

        for (int index = 0; index < template.length(); index++) {
            char c = template.charAt(index);
            if (c == '{' && !isCurrentInKeyword) {
                isCurrentInKeyword = true;
                keyword = new StringBuilder("{");

                if (content != null) {
                    parts.add(content.toString());
                    content = null;
                }
                continue;
            }

            if (c == '}' && isCurrentInKeyword) {
                isCurrentInKeyword = false;
                keyword.append("}");
                parts.add(keyword.toString());
                keyword = null;
                continue;
            }

            if (isCurrentInKeyword) {
                if (!Character.isWhitespace(c)) {
                    keyword.append(c);
                }
                continue;
            }

            if (content == null) {
                content = new StringBuilder();
            }
            content.append(c);
        }

        if (keyword != null) {
            parts.add(keyword.toString());
        }

        if (content != null) {
            parts.add(content.toString());
        }
    }


    public static SimplePromptTemplate create(String template) {
        return new SimplePromptTemplate(template);
    }

    public SimplePrompt format(Map<String, Object> params) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.charAt(0) == '{' && part.charAt(part.length() - 1) == '}') {
                if (part.length() > 2) {
                    String key = part.substring(1, part.length() - 1);
                    Object value = getParams(key, params);
                    result.append(value == null ? "" : value);
                }
            } else {
                result.append(part);
            }
        }
        return new SimplePrompt(result.toString());
    }

    public Set<String> getKeys() {
        return keys;
    }

    public List<String> getParts() {
        return parts;
    }

    private Object getParams(String keysString, Map<String, Object> params) {
        //todo 支持通过 "." 访问属性或者方法
        return params != null ? params.get(keysString) : null;
    }

    @Override
    public String toString() {
        return "SimplePromptTemplate{" +
            "keys=" + keys +
            ", parts=" + parts +
            '}';
    }
}
