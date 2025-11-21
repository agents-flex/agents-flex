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
package com.agentsflex.core.agent.react;

import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.message.Message;

import java.util.List;

public class ReActMessageBuilder {


    /**
     * 构建 ReAct 开始消息
     *
     * @param prompt    提示词
     * @param tools 函数列表
     * @param userQuery 用户问题
     * @return 返回 HumanMessage
     */
    public Message buildStartMessage(String prompt, List<Tool> tools, String userQuery) {
        UserMessage message = new UserMessage(prompt);
        message.addMetadata("tools", tools);
        message.addMetadata("user_input", userQuery);
        message.addMetadata("type", "reActWrapper");
        return message;
    }


    /**
     * 构建 JSON 解析错误消息，用于 json 发送错误时，让 AI 自动修正
     *
     * @param e    错误信息
     * @param step 发送错误的步骤
     * @return 返回 HumanMessage
     */
    public Message buildJsonParserErrorMessage(Exception e, ReActStep step) {
        String errorMsg = "JSON 解析失败: " + e.getMessage() + ", 原始内容: " + step.getActionInput();
        String observation = "Action：" + step.getAction() + "\n"
            + "Action Input：" + step.getActionInput() + "\n"
            + "Error：" + errorMsg + "\n"
            + "请检查你的 Action Input 格式是否正确，并纠正 JSON 内容重新生成响应。\n";
        UserMessage userMessage = new UserMessage(observation + "请继续推理下一步。");
        userMessage.addMetadata("type", "reActObservation");
        return userMessage;
    }

    /**
     * 构建 Observation 消息，让 AI 自动思考
     *
     * @param step   步骤
     * @param result 步骤结果
     * @return 步骤结果消息
     */
    public Message buildObservationMessage(ReActStep step, Object result) {
        String observation = buildObservationString(step, result);
        UserMessage userMessage = new UserMessage(observation + "\n请继续推理下一步。");
        userMessage.addMetadata("type", "reActObservation");
        return userMessage;
    }


    /**
     * 构建 Observation 字符串
     *
     * @param step   步骤
     * @param result 步骤结果
     * @return 步骤结果字符串
     */
    public static String buildObservationString(ReActStep step, Object result) {
        return "Action：" + step.getAction() + "\n" +
            "Action Input：" + step.getActionInput() + "\n" +
            "Action Result：" + result + "\n";
    }

    /**
     * 构建 Action 错误消息，用于 Action 错误时，让 AI 自动修正
     *
     * @param step 步骤
     * @param e    错误信息
     * @return 错误消息
     */
    public Message buildActionErrorMessage(ReActStep step, Exception e) {
        // 将错误信息反馈给 AI，让其修正
        String observation = buildObservationString(step, "Error: " + e.getMessage()) + "\n"
            + "请根据错误信息调整参数并重新尝试。\n";
        UserMessage userMessage = new UserMessage(observation + "请继续推理下一步。");
        userMessage.addMetadata("type", "reActObservation");
        return userMessage;
    }
}
