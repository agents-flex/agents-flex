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
package com.agentsflex.core.agents.react;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface ReActStepParser {

    ReActStepParser DEFAULT = new ReActStepParser() {
        private final Pattern STEP_PATTERN = Pattern.compile(
            "Thought: (.*?)\n" +
                "Action: (.*?)\n" +
                "Action Input: (\\{[\\s\\S]*})",
            Pattern.DOTALL
        );

        @Override
        public List<ReActStep> parse(String content) {
            List<ReActStep> steps = new ArrayList<>();
            Matcher matcher = STEP_PATTERN.matcher(content);

            while (matcher.find()) {
                String thought = matcher.group(1).trim();
                String action = matcher.group(2).trim();
                String actionInput = matcher.group(3).trim();

                steps.add(new ReActStep(thought, action, actionInput));
            }

            return steps;
        }
    };


    List<ReActStep> parse(String content);


    default boolean isFinalAnswer(String content) {
        return content.contains(getFinalAnswerFlag());
    }

    default boolean isReActAction(String content) {
        return content.contains("Action:") && content.contains("Action Input:");
    }

    default String getFinalAnswerFlag() {
        return "Final Answer:";
    }
}
