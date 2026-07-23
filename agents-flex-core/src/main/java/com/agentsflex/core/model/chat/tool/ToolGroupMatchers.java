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
package com.agentsflex.core.model.chat.tool;

import com.agentsflex.core.util.StringUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.regex.Pattern;

/** Common matching strategies for the latest user prompt. */
public final class ToolGroupMatchers {

    private ToolGroupMatchers() {
    }

    public static ToolGroupMatcher always() {
        return context -> true;
    }

    public static ToolGroupMatcher promptContains(String... keywords) {
        return promptContains(keywords == null ? null : Arrays.asList(keywords), false);
    }

    public static ToolGroupMatcher promptContains(Collection<String> keywords, boolean ignoreCase) {
        return context -> {
            String prompt = context.getUserPrompt();
            if (prompt == null || keywords == null || keywords.isEmpty()) {
                return false;
            }
            String candidate = ignoreCase ? prompt.toLowerCase(java.util.Locale.ROOT) : prompt;
            for (String keyword : keywords) {
                if (!StringUtil.hasText(keyword)) {
                    continue;
                }
                String expected = ignoreCase ? keyword.toLowerCase(java.util.Locale.ROOT) : keyword;
                if (candidate.contains(expected)) {
                    return true;
                }
            }
            return false;
        };
    }

    public static ToolGroupMatcher promptMatches(String regex) {
        if (regex == null) {
            throw new IllegalArgumentException("regex must not be null");
        }
        return promptMatches(Pattern.compile(regex));
    }

    public static ToolGroupMatcher promptMatches(Pattern pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("pattern must not be null");
        }
        return context -> context.getUserPrompt() != null
            && pattern.matcher(context.getUserPrompt()).find();
    }
}
