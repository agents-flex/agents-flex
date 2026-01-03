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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public interface ReActStepParser {

    ReActStepParser DEFAULT = content -> {
        if (content == null || content.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<ReActStep> steps = new ArrayList<>();
        String[] lines = content.split("\n");

        String currentThought = null;
        String currentAction = null;
        String currentRequest = null;
        StringBuilder currentActionInput = new StringBuilder();
        boolean inActionInput = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // 如果遇到新的 Thought，且已有完整步骤，先保存
            if (trimmedLine.startsWith("Thought:")) {
                if (currentThought != null && currentAction != null) {
                    // 保存上一个完整的 step（在遇到新 Thought 或 Final Answer 时触发）
                    steps.add(new ReActStep(currentThought, currentAction, currentActionInput.toString().trim()));
                    // 重置状态
                    currentActionInput.setLength(0);
                    inActionInput = false;
                }
                currentThought = trimmedLine.substring("Thought:".length()).trim();
                currentAction = null;
            }
            // 如果遇到 Action
            else if (trimmedLine.startsWith("Action:")) {
                if (currentThought == null) {
                    // 如果 Action 出现在 Thought 之前，视为格式错误，可选择忽略或报错
                    continue;
                }
                currentAction = trimmedLine.substring("Action:".length()).trim();
            }
            // 如果遇到 Action Input
            else if (trimmedLine.startsWith("Action Input:")) {
                if (currentAction == null) {
                    // Action Input 出现在 Action 之前，跳过
                    continue;
                }
                String inputPart = trimmedLine.substring("Action Input:".length()).trim();
                currentActionInput.append(inputPart);
                inActionInput = true;
            }
            // 如果正在读取 Action Input 的后续行（多行 JSON）
            else if (inActionInput) {
                // 判断是否是下一个结构的开始：Thought / Action / Final Answer
                if (trimmedLine.startsWith("Thought:") ||
                    trimmedLine.startsWith("Action:") ||
                    trimmedLine.startsWith("Final Answer:")) {
                    // 实际上这一行属于下一段，应退回处理
                    // 但我们是在 for 循环里，无法“退回”，所以先保存当前 step
                    steps.add(new ReActStep(currentThought, currentAction, currentActionInput.toString().trim()));
                    currentActionInput.setLength(0);
                    inActionInput = false;
                    currentThought = null;
                    currentAction = null;
                    // 重新处理当前行（递归或标记），但为简化，我们直接继续下一轮
                    // 因为下一轮会处理 Thought/Action
                    continue;
                } else {
                    // 是 Action Input 的续行，追加（保留原始换行或加空格）
                    if (currentActionInput.length() > 0) {
                        currentActionInput.append("\n");
                    }
                    currentActionInput.append(line); // 保留原始缩进（可选）
                }
            }
            // 如果遇到 Final Answer，结束当前步骤（如果有）
            else if (trimmedLine.startsWith("Final Answer:")) {
                if (currentThought != null && currentAction != null) {
                    steps.add(new ReActStep(currentThought, currentAction, currentActionInput.toString().trim()));
                }
                // Final Answer 本身不作为 ReActStep，通常单独处理
                break; // 或 continue，视需求而定
            }
            // 空行或无关行：如果是 Action Input 多行内容，已在上面处理；否则忽略
        }

        // 循环结束后，检查是否还有未保存的步骤
        if (currentThought != null && currentAction != null) {
            steps.add(new ReActStep(currentThought, currentAction, currentActionInput.toString().trim()));
        }

        return steps;
    };


    List<ReActStep> parse(String content);


    default boolean isFinalAnswer(String content) {
        return content.contains(getFinalAnswerFlag());
    }

    default boolean isReActAction(String content) {
        return content.contains("Action:") && content.contains("Action Input:");
    }

    default boolean isRequest(String content) {
        return content.contains("Request:");
    }

    default String extractRequestQuestion(String content) {
        return content.trim().substring("Request:".length()).trim();
    }

    default String getFinalAnswerFlag() {
        return "Final Answer:";
    }
}
