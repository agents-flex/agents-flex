///*
// *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
// *  <p>
// *  Licensed under the Apache License, Version 2.0 (the "License");
// *  you may not use this file except in compliance with the License.
// *  You may obtain a copy of the License at
// *  <p>
// *  http://www.apache.org/licenses/LICENSE-2.0
// *  <p>
// *  Unless required by applicable law or agreed to in writing, software
// *  distributed under the License is distributed on an "AS IS" BASIS,
// *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// *  See the License for the specific language governing permissions and
// *  limitations under the License.
// */
//package com.agentsflex.core.agent.react;
//
//import com.agentsflex.core.model.chat.tool.Parameter;
//import com.agentsflex.core.model.chat.tool.Tool;
//import com.agentsflex.core.model.chat.tool.ToolContextHolder;
//
//import java.util.Map;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.TimeUnit;
//
//public class ReActAgentTool implements Tool {
//
//    public static final String PARENT_AGENT_KEY = "__parent_react_agent";
//
//    private final ReActAgent subAgent;
//    private String name;
//    private String description;
//
//    public ReActAgentTool(ReActAgent subAgent) {
//        this.subAgent = subAgent;
//    }
//
//
//    @Override
//    public String getName() {
//        return name;
//    }
//
//    @Override
//    public String getDescription() {
//        return description;
//    }
//
//    @Override
//    public Parameter[] getParameters() {
//        return new Parameter[0];
//    }
//
//    @Override
//    public Object invoke(Map<String, Object> argsMap) {
//        ReActAgent parentAgent = ToolContextHolder.currentContext().getAttribute(PARENT_AGENT_KEY);
//
//
//        if (parentAgent != null) {
//            // @todo 获取父 agent 的监听器 和 历史消息，传入给 sub Agent
//        }
//
//        SyncReActListener listener = new SyncReActListener();
//        subAgent.addListener(listener);
//
//        subAgent.execute();
//
//        try {
//            return listener.getFinalAnswer(1000, TimeUnit.MILLISECONDS);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            throw new RuntimeException("ReActAgent execution was interrupted", e);
//        }
//    }
//
//
//    // 同步监听器（内部类或独立类）
//    public static class SyncReActListener implements ReActAgentListener {
//        private final CountDownLatch latch = new CountDownLatch(1);
//        private String finalAnswer;
//        private Exception error;
//
//        @Override
//        public void onFinalAnswer(String answer) {
//            this.finalAnswer = answer;
//            latch.countDown();
//        }
//
//        @Override
//        public void onError(Exception e) {
//            this.error = e;
//            latch.countDown();
//        }
//
//        @Override
//        public void onMaxIterationsReached() {
//            this.error = new RuntimeException("ReActAgent reached max iterations without final answer");
//            latch.countDown();
//        }
//
//        // 其他回调可留空
//        @Override
//        public void onActionStart(ReActStep step) {
//        }
//
//        @Override
//        public void onActionEnd(ReActStep step, Object result) {
//        }
//
//        public String getFinalAnswer(long timeout, TimeUnit unit) throws InterruptedException {
//            if (!latch.await(timeout, unit)) {
//                throw new RuntimeException("ReActAgent execution timed out");
//            }
//            if (error != null) {
//                throw new RuntimeException("ReActAgent execution failed", error);
//            }
//            if (finalAnswer == null) {
//                throw new RuntimeException("ReActAgent did not produce a final answer");
//            }
//            return finalAnswer;
//        }
//    }
//}
