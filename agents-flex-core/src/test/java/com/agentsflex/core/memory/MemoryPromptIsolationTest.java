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
package com.agentsflex.core.memory;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.prompt.MemoryPrompt;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * 验证 DefaultChatMemory.getMessages() 返回的是独立副本，
 * 以及 MemoryPrompt.getMessages() 在 systemMessage / truncate 场景下不污染内部状态。
 */
public class MemoryPromptIsolationTest {

    // ---------------------------------------------------------------
    // DefaultChatMemory 单元测试
    // ---------------------------------------------------------------

    /**
     * 当请求条数等于消息总数时，返回副本，外部修改不影响内部。
     */
    @Test
    public void getMessages_fullCount_returnsCopy() {
        DefaultChatMemory memory = new DefaultChatMemory();
        memory.addMessage(new UserMessage("hi"));
        memory.addMessage(new AiMessage("hello"));

        List<Message> result = memory.getMessages(2);
        result.clear(); // 对返回值做破坏性操作

        // 内部 messages 不受影响
        Assert.assertEquals(2, memory.getMessages(2).size());
    }

    /**
     * 当请求条数小于消息总数（走 subList 分支）时，返回副本，外部 add/set 不影响内部。
     * 修复前：subList 视图上调用 add(0, ...) 会抛 UnsupportedOperationException
     *         或直接修改内部列表。
     */
    @Test
    public void getMessages_partialCount_returnsCopyNotSubListView() {
        DefaultChatMemory memory = new DefaultChatMemory();
        memory.addMessage(new UserMessage("msg1"));
        memory.addMessage(new UserMessage("msg2"));
        memory.addMessage(new UserMessage("msg3"));

        // 只取最近 2 条
        List<Message> result = memory.getMessages(2);
        Assert.assertEquals(2, result.size());

        // 对返回值做破坏性操作——修复前此处会间接修改 memory 内部
        result.add(0, new UserMessage("injected"));
        result.set(1, new UserMessage("replaced"));

        // 内部 messages 不受影响，仍是原始 3 条
        List<Message> after = memory.getMessages(3);
        Assert.assertEquals(3, after.size());
        Assert.assertEquals("msg1", ((UserMessage) after.get(0)).getContent());
        Assert.assertEquals("msg2", ((UserMessage) after.get(1)).getContent());
        Assert.assertEquals("msg3", ((UserMessage) after.get(2)).getContent());
    }

    // ---------------------------------------------------------------
    // MemoryPrompt 集成场景
    // ---------------------------------------------------------------

    /**
     * 场景：消息数 < maxAttachedMessageCount，且设置了 systemMessage。
     * 修复前：getMessages() 内对 subList 视图调用 add(0, systemMessage)
     *         → UnsupportedOperationException，Agent 直接崩溃。
     */
    @Test
    public void getMessages_withSystemMessage_doesNotThrow() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("You are a helpful assistant.");
        prompt.addUserMessage("hello");
        prompt.addAiMessage("hi");

        // maxAttachedMessageCount 默认 100，history=2 < 100，走 subList 分支
        // 修复前此处抛 UnsupportedOperationException
        List<Message> messages = prompt.getMessages();

        // system message 应插入到首位
        Assert.assertTrue(messages.get(0) instanceof com.agentsflex.core.message.SystemMessage);
        Assert.assertTrue(messages.size() >= 3);
    }

    /**
     * 场景：开启 historyMessageTruncateEnable，消息数 < maxAttachedMessageCount。
     * 修复前：truncate 对 subList 视图调用 set(i, copied)，由于 subList 是原列表视图，
     *         set() 会永久篡改 DefaultChatMemory 内部 messages 中存储的对象引用。
     * 验证方式：直接访问 memory.getMessages() 检查原始存储，绕过 MemoryPrompt truncate 逻辑。
     */
    @Test
    public void getMessages_withTruncate_doesNotCorruptInternalMessages() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setHistoryMessageTruncateEnable(true);
        prompt.setHistoryMessageTruncateLength(5); // 截断为 5 个字符

        String longContent = "This is a very long message that exceeds the truncate limit";
        prompt.addUserMessage(longContent);
        prompt.addAiMessage("ok");

        // 触发截断，返回值里的消息内容是截断后的（预期行为）
        List<Message> truncated = prompt.getMessages();
        Assert.assertTrue(((UserMessage) truncated.get(0)).getContent().length() <= 5);

        // 直接访问内部 memory，不经过 MemoryPrompt truncate——原始存储应完整
        // 修复前：subList.set() 会用 copied 替换内部列表的引用，导致此处拿到截断后的内容
        List<Message> fromMemory = prompt.getMemory().getMessages(100);
        Assert.assertEquals(longContent, ((UserMessage) fromMemory.get(0)).getContent());
    }

    /**
     * 多轮对话场景：确认 getMessages() 返回的消息列表在每次调用后
     * 不因外部修改而影响下一轮的内存完整性。
     */
    @Test
    public void getMessages_multipleRounds_memoryRemainsIntact() {
        DefaultChatMemory memory = new DefaultChatMemory();
        for (int i = 1; i <= 5; i++) {
            memory.addMessage(new UserMessage("user-" + i));
        }

        // 模拟 Agent 每轮取最近 3 条
        List<Message> round1 = memory.getMessages(3);
        round1.add(new AiMessage("injected")); // 外部修改

        List<Message> round2 = memory.getMessages(3);
        Assert.assertEquals(3, round2.size());
        // round2 应仍是 user-3 / user-4 / user-5
        Assert.assertEquals("user-3", ((UserMessage) round2.get(0)).getContent());
        Assert.assertEquals("user-5", ((UserMessage) round2.get(2)).getContent());
    }
}
