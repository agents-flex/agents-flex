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

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.functions.Function;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


public class SimplePrompt extends Prompt {

    protected SystemMessage systemMessage;
    protected UserMessage userMessage;
    protected List<ToolMessage> toolMessages;

    public SimplePrompt() {
        this.userMessage = new UserMessage();
    }

    public SimplePrompt(String content) {
        this.userMessage = new UserMessage(content);
    }


    /// /// functions
    public void addFunction(Function function) {
        userMessage.addFunction(function);
    }

    public void addFunctions(Collection<? extends Function> functions) {
        userMessage.addFunctions(functions);
    }

    public void addFunctionsFromClass(Class<?> funcClass, String... methodNames) {
        userMessage.addFunctionsFromClass(funcClass, methodNames);
    }

    public void addFunctionsFromObject(Object funcObject, String... methodNames) {
        userMessage.addFunctionsFromObject(funcObject, methodNames);
    }


    public List<Function> getFunctions() {
        return userMessage.getFunctions();
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

    public List<String> getImageBase64s() {
        return userMessage.getImageBase64s();
    }

    public void setImageBase64s(List<String> imageBase64s) {
        userMessage.setImageBase64s(imageBase64s);
    }

    public void addImageBase64(String imageBase64) {
        userMessage.addImageBase64(imageBase64);
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

    public List<ToolMessage> getToolMessages() {
        return toolMessages;
    }

    public void setToolMessages(List<ToolMessage> toolMessages) {
        this.toolMessages = toolMessages;
    }

    @Override
    public List<Message> toMessages() {
        List<Message> messages = new ArrayList<>(2);
        if (systemMessage != null) {
            messages.add(systemMessage);
        }
        messages.add(userMessage);
        if (toolMessages != null) {
            messages.addAll(toolMessages);
        }
        return messages;
    }
}
