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
package com.agentsflex.core.prompt;

import com.agentsflex.core.message.*;
import com.agentsflex.core.model.chat.tool.Tool;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


public class SimplePrompt extends Prompt {

    protected SystemMessage systemMessage;
    protected UserMessage userMessage;
    protected AiMessage aiMessage;
    protected List<ToolMessage> toolMessages;

    public SimplePrompt() {
        this.userMessage = new UserMessage();
    }

    public SimplePrompt(String content) {
        this.userMessage = new UserMessage(content);
    }


    /// /// Tools
    public void addTool(Tool tool) {
        userMessage.addTool(tool);
    }

    public void addTools(Collection<? extends Tool> Tools) {
        userMessage.addTools(Tools);
    }

    public void addToolsFromClass(Class<?> funcClass, String... methodNames) {
        userMessage.addToolsFromClass(funcClass, methodNames);
    }

    public void addToolsFromObject(Object funcObject, String... methodNames) {
        userMessage.addToolsFromObject(funcObject, methodNames);
    }


    public List<Tool> getTools() {
        return userMessage.getTools();
    }


    public String getToolChoice() {
        return userMessage.getToolChoice();
    }

    public void setToolChoice(String toolChoice) {
        userMessage.setToolChoice(toolChoice);
    }


    /// /// Audio
    public List<String> getAudioUrls() {
        return userMessage.getAudioUrls();
    }

    public void setAudioUrls(List<String> audioUrls) {
        userMessage.setAudioUrls(audioUrls);
    }

    public void addAudioUrl(String audioUrl) {
        userMessage.addAudioUrl(audioUrl);
    }


    /// ///  Video
    public List<String> getVideoUrls() {
        return userMessage.getVideoUrls();
    }

    public void setVideoUrls(List<String> videoUrls) {
        userMessage.setVideoUrls(videoUrls);
    }

    public void addVideoUrl(String videoUrl) {
        userMessage.addVideoUrl(videoUrl);
    }


    /// /// Images
    public List<String> getImageUrls() {
        return userMessage.getImageUrls();
    }

    public void setImageUrls(List<String> imageUrls) {
        userMessage.setImageUrls(imageUrls);
    }

    public void addImageUrl(String imageUrl) {
        userMessage.addImageUrl(imageUrl);
    }

    public void addImageFile(File imageFile) {
        userMessage.addImageFile(imageFile);
    }

    public void addImageBytes(byte[] imageBytes, String mimeType) {
        userMessage.addImageBytes(imageBytes, mimeType);
    }


    /// ////getter setter
    public SystemMessage getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(SystemMessage systemMessage) {
        this.systemMessage = systemMessage;
    }

    public UserMessage getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(UserMessage userMessage) {
        this.userMessage = userMessage;
    }

    public AiMessage getAiMessage() {
        return aiMessage;
    }

    public void setAiMessage(AiMessage aiMessage) {
        this.aiMessage = aiMessage;
    }

    public List<ToolMessage> getToolMessages() {
        return toolMessages;
    }

    public void setToolMessages(List<ToolMessage> toolMessages) {
        this.toolMessages = toolMessages;
    }

    @Override
    public List<Message> getMessages() {
        List<Message> messages = new ArrayList<>(2);
        if (systemMessage != null) {
            messages.add(systemMessage);
        }
        messages.add(userMessage);

        if (aiMessage != null) {
            messages.add(aiMessage);
        }

        if (toolMessages != null) {
            messages.addAll(toolMessages);
        }
        return messages;
    }
}
