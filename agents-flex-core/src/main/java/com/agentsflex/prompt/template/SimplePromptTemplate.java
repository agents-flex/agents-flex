/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SimplePromptTemplate implements PromptTemplate<SimplePrompt>{

    protected final String template;
    protected Map<String, List<Integer>> keywordPositions = new HashMap<>();

    public SimplePromptTemplate(String template) {
        boolean isCurrentInKeyword = false;
        StringBuilder keyword = null;

        StringBuilder newTemplate = new StringBuilder();
        int newTemplatePosition = 0;
        for (int index = 0; index < template.length(); index++) {
            char c = template.charAt(index);

            if (c == '{' && !isCurrentInKeyword) {
                isCurrentInKeyword = true;
                keyword = new StringBuilder();
                continue;
            }

            if (c == '}' && isCurrentInKeyword) {
                List<Integer> positions = keywordPositions.get(keyword.toString());
                if (positions == null) {
                    positions = new ArrayList<>(1);
                }
                positions.add(newTemplatePosition);
                keywordPositions.put(keyword.toString(), positions);

                isCurrentInKeyword = false;
                keyword = null;
                continue;
            }

            if (isCurrentInKeyword && !Character.isWhitespace(c)) {
                keyword.append(c);
            } else if (!isCurrentInKeyword) {
                newTemplate.append(c);
                newTemplatePosition++;
            }
        }

        this.template = newTemplate.toString();
    }


    public static SimplePromptTemplate create(String template) {
        return new SimplePromptTemplate(template);
    }

    public SimplePrompt format(Map<String, Object> params) {
        StringBuilder result = new StringBuilder(this.template);

        int offset = 0;
        for (String param : this.keywordPositions.keySet()) {
            Object value = params.get(param);
            if (value == null) {
                continue;
            } else {
                value = value.toString();
            }
            for (Integer position : this.keywordPositions.get(param)) {
                result.insert(position + offset, value);
                offset += ((String) value).length();
            }
        }
        return new SimplePrompt(result.toString());
    }

}
