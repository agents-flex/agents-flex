package com.agentsflex.agent;

import com.agentsflex.agent.compression.*;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.model.chat.deepseek.DeepseekChatModel;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;
import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * 真实 DeepSeek 集成验证。默认跳过；执行前设置 DEEPSEEK_API_KEY，密钥绝不写入源码或测试报告。
 */
public class AgentContextCompressorsDeepseekIntegrationTest {

    @Test
    public void shouldCompressWithRealDeepseekModel() {
        DeepseekChatModel model = model();
        List<Message> history = history();

        List<Message> identity = AgentContextCompressors.identity().compress(history);
        assertEquals(history.size(), identity.size());
        assertNotSame(history.get(0), identity.get(0));

        List<Message> compact = AgentContextCompressors.compactCompletedTurns().compress(history);
        assertEquals(14, compact.size());
        assertTrue(compact.stream().noneMatch(message -> message instanceof ToolMessage));

        List<Message> excerpt = AgentContextCompressors.textExcerpt(120).compress(history);
        assertEquals(1, excerpt.size());
        assertTrue(excerpt.get(0) instanceof UserMessage);

        List<Message> summary = AgentContextCompressors.model(model,
                "请用中文简要总结历史对话，保留订单号、日期和用户约束，不要调用工具。")
            .compress(history);
        assertEquals(2, summary.size());
        assertTrue(summary.get(0) instanceof UserMessage);
        assertTrue(summary.get(1) instanceof AiMessage);
        assertTrue(summary.get(1).getTextContent().length() > 0);

        List<Message> perMessage = AgentContextCompressors.perMessageModel(model,
                AgentContextModelCompressorOptions.builder()
                    .instruction("请逐条压缩每条消息，保留订单号、日期和用户约束。仅返回 JSON 数组，每项必须有 messageId 和 summary。")
                    .chatOptions(ChatOptions.builder()
                        .temperature(0.1f)
                        .maxTokens(2_000)
                        .thinkingEnabled(false)
                        .build())
                    .build())
            .compress(history);
        assertEquals(history.size(), perMessage.size());
        for (int index = 0; index < history.size(); index++) {
            assertEquals(history.get(index).getClass(), perMessage.get(index).getClass());
            assertEquals(history.get(index).getMessageId(), perMessage.get(index).getMessageId());
            if (!(perMessage.get(index) instanceof AiMessage
                && ((AiMessage) perMessage.get(index)).hasToolCalls())) {
                assertFalse(perMessage.get(index).getTextContent().isEmpty());
            }
        }
    }

    @Test
    public void shouldApplyModelCompressorThroughAgentRunnerConfiguration() {
        DeepseekChatModel model = model();
        List<Message> history = history();
        AtomicReference<Prompt> captured = new AtomicReference<>();
        AgentContextCompressor compressor = AgentContextCompressors.model(model,
            "请将较早历史压缩为事实摘要，保留订单号、时间、地点、人数和限制条件，不要调用工具。");
        Agent agent = Agent.builder("deepseek-context-agent")
            .chatModel(model)
            .maxAttachedTurns(4)
            .maxAttachedMessages(12)
            .compressionPolicy(AgentContextCompressionPolicy.builder()
                .compressor(compressor)
                .keepRecentTurns(1)
                .compactCompletedToolTurns(true)
                .build())
            .middleware(new AgentMiddleware() {
                @Override
                public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                         AgentModelCallChain chain) {
                    captured.set(context.getPrompt());
                    return chain.proceed(context);
                }
            })
            .build();

        assertEquals(4, agent.getMaxAttachedTurns());
        assertEquals(12, agent.getMaxAttachedMessages());
        assertEquals(1, agent.getCompressionPolicy().getKeepRecentTurns());
        assertTrue(agent.getCompressionPolicy().isCompactCompletedToolTurns());
        assertTrue(agent.getCompressionPolicy().getCompressor() == compressor);
        AgentTurn turn = new AgentRunner().run(agent, history,
            new UserMessage("请根据历史预约信息，确认最终会议安排并简要说明。"));
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertTrue(turn.getFinalOutput() != null && !turn.getFinalOutput().isEmpty());
        assertTrue(captured.get() instanceof com.agentsflex.core.prompt.MemoryPrompt);
        List<Message> modelMessages = captured.get().getMessages();
        assertTrue(modelMessages.get(0) instanceof UserMessage);
        assertTrue(modelMessages.size() < history.size());
        // The model may choose different wording or omit individual facts;
        // compression is verified by the reduced, valid prompt shape above.
        assertTrue(modelMessages.size() <= 12);
    }

    @Test
    public void shouldProtectRecentToolTurnsAndCompactOlderToolTurns() {
        MemoryPrompt source = new MemoryPrompt();
        source.addMessages(history());
        List<Message> modelMessages = AgentContextWindow.build(source, 4, 100,
            true, 2, null).getMemory().getMessages(Integer.MAX_VALUE);

        // 选中的 4 个 Turn 中，较早的 call-4 被压缩；最近两轮（其中最后一轮是当前 Turn）保留协议。
        assertTrue(modelMessages.stream().noneMatch(message -> message instanceof ToolMessage
            && "call-4".equals(((ToolMessage) message).getToolCallId())));
        assertTrue(modelMessages.stream().anyMatch(message -> message instanceof ToolMessage
            && "call-8".equals(((ToolMessage) message).getToolCallId())));
        assertTrue(modelMessages.get(0) instanceof UserMessage);
    }

    private DeepseekChatModel model() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assume.assumeTrue("DEEPSEEK_API_KEY is required for integration test", apiKey != null && !apiKey.trim().isEmpty());
        DeepseekConfig config = new DeepseekConfig();
        config.setApiKey(apiKey);
        return new DeepseekChatModel(config);
    }

    private List<Message> history() {
        List<Message> result = new ArrayList<>();
        result.add(new UserMessage("我需要预订订单号 AF-20260808 对应的会议室，参会人数 12 人，日期是下周三。"));
        result.add(new AiMessage("已记录订单号 AF-20260808、12 人和下周三的会议需求。"));
        result.add(new UserMessage("会议必须在上海办公室，优先下午两点以后，不能安排在周二。"));
        result.addAll(toolTurn("call-2", "查询上海会议室库存", "上海有 3 个可用会议室。"));
        result.add(new UserMessage("会议主题是年度技术复盘，需要白板、投影和远程视频设备。"));
        result.add(new AiMessage("已记录年度技术复盘，并需要白板、投影和远程视频设备。"));
        result.add(new UserMessage("参会人包括研发、产品和客户代表，预计需要三个小时。"));
        result.addAll(toolTurn("call-4", "查询设备", "白板、投影和视频设备均可提供。"));
        result.add(new UserMessage("如果上海办公室没有合适房间，可以选择浦东备用会议中心。"));
        result.addAll(toolTurn("call-6", "查询备用会议中心", "浦东备用会议中心有可用房间。"));
        result.add(new UserMessage("通知中需要包含会议主题、时间、地点和参会人数，不要发送订单内部备注。"));
        result.add(new AiMessage("通知只包含会议主题、时间、地点和人数，不包含内部备注。"));
        result.add(new UserMessage("请检查最终房间是否满足全部条件。"));
        result.addAll(toolTurn("call-8", "检查最终房间", "ROOM-SH-101 满足全部条件。"));
        return result;
    }

    private static List<Message> toolTurn(String id, String name, String result) {
        AiMessage callMessage = new AiMessage();
        callMessage.setToolCalls(Arrays.asList(new ToolCall(id, name, "{}")));
        ToolMessage toolMessage = new ToolMessage();
        toolMessage.setToolCallId(id);
        toolMessage.setContent(result);
        return Arrays.<Message>asList(callMessage, toolMessage, new AiMessage("工具结果已确认。"));
    }
}
