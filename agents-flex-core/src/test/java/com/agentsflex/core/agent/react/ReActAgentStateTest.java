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

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 复现并验证 GitHub issue #50：ReActAgentState 的 toJSON()/fromJSON() 往返序列化。
 *
 * <p>修复前，{@link ReActAgentState#fromJSON(String)} 使用 {@code SupportClassForName}，
 * 无法解析 {@code @type} 标记，fastjson2 会尝试实例化抽象类 {@link Message} 并抛出
 * {@code create instance error}。修复后使用 {@code SupportAutoType}，可正确还原
 * {@link UserMessage}/{@link AiMessage} 等具体子类型。
 *
 * <p>本测试不依赖网络或 LLM，可离线运行。
 */
public class ReActAgentStateTest {

    @Test
    public void testToJsonFromJsonRoundTrip_restoresConcreteMessageSubtypes() {
        ReActAgentState state = new ReActAgentState();
        state.setUserQuery("今天的天气怎么样？");
        state.setIterationCount(3);
        state.setMaxIterations(20);
        state.setStreamable(true);
        state.setContinueOnActionInvokeError(true);

        UserMessage userMessage = new UserMessage("我在北京市");
        userMessage.putMetadata("type", "reActObservation");

        AiMessage aiMessage = new AiMessage();
        aiMessage.setContent("Final Answer: 北京今天晴。");
        aiMessage.putMetadata("type", "reActFinalAnswer");

        List<Message> history = new ArrayList<>();
        history.add(userMessage);
        history.add(aiMessage);
        state.setMessageHistory(history);

        // 序列化 -> 反序列化（修复前此处会抛出 JSONException: create instance error）
        String json = state.toJSON();
        ReActAgentState restored = ReActAgentState.fromJSON(json);

        assertNotNull(restored);

        // 标量字段往返保持
        assertEquals("今天的天气怎么样？", restored.getUserQuery());
        assertEquals(3, restored.getIterationCount());
        assertEquals(20, restored.getMaxIterations());
        assertTrue(restored.isStreamable());
        assertTrue(restored.isContinueOnActionInvokeError());

        // 关键：messageHistory 中的具体子类型被正确还原
        List<Message> restoredHistory = restored.getMessageHistory();
        assertNotNull(restoredHistory);
        assertEquals(2, restoredHistory.size());

        assertTrue("第一个消息应还原为 UserMessage", restoredHistory.get(0) instanceof UserMessage);
        assertTrue("第二个消息应还原为 AiMessage", restoredHistory.get(1) instanceof AiMessage);

        UserMessage restoredUser = (UserMessage) restoredHistory.get(0);
        assertEquals("我在北京市", restoredUser.getContent());
        assertEquals("reActObservation", restoredUser.getMetadata("type"));

        AiMessage restoredAi = (AiMessage) restoredHistory.get(1);
        assertEquals("Final Answer: 北京今天晴。", restoredAi.getContent());
        assertEquals("reActFinalAnswer", restoredAi.getMetadata("type"));
    }

    @Test
    public void testFromJson_emptyHistory_roundTrips() {
        ReActAgentState state = new ReActAgentState();
        state.setUserQuery("hello");

        ReActAgentState restored = ReActAgentState.fromJSON(state.toJSON());

        assertNotNull(restored);
        assertEquals("hello", restored.getUserQuery());
        assertNull(restored.getMessageHistory());
    }
}
