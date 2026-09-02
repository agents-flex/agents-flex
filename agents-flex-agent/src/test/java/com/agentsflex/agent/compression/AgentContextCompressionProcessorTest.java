package com.agentsflex.agent.compression;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

import static org.junit.Assert.*;

/**
 * 增量压缩状态、触发边界和多轮 CAS 保存的综合测试。
 */
public class AgentContextCompressionProcessorTest {

    @Test
    public void shouldReturnPendingMessagesWithoutCompressingBeforeThreshold() {
        MemoryStore store = new MemoryStore();
        AtomicInteger compressions = new AtomicInteger();
        AgentContextCompressionProcessor processor = createProcessor(store,
            input -> input.getEstimatedPendingTokens() >= 4,
            value -> {
                compressions.incrementAndGet();
                return Collections.singletonList(summary("summary"));
            },
            messages -> messages.size());
        List<Message> history = messages(3);

        AgentContextCompressionResult result = processor.process("c1", history);

        assertFalse(result.isCompressed());
        assertEquals(history.size(), result.getModelMessages().size());
        assertEquals(0, compressions.get());
        assertEquals(0, store.saves.size());
    }

    @Test
    public void shouldPersistInitialCompressionWithVersionZeroCas() {
        MemoryStore store = new MemoryStore();
        AtomicReference<List<Message>> compressorInput = new AtomicReference<>();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> {
                compressorInput.set(value);
                return Collections.singletonList(summary("summary-1"));
            },
            messages -> messages.size());
        List<Message> history = messages(100);

        AgentContextCompressionResult result = processor.process("c1", history);

        assertTrue(result.isCompressed());
        assertEquals(100, compressorInput.get().size());
        assertEquals(0L, store.saves.get(0).expectedVersion);
        assertEquals(1L, result.getState().getVersion());
        assertEquals(history.get(99).getMessageId(), result.getState().getCoveredUntilMessageId());
        assertEquals(1, result.getModelMessages().size());
    }

    @Test
    public void shouldCompressOnlyNewMessagesAcrossMultipleRounds() {
        MemoryStore store = new MemoryStore();
        List<List<Message>> inputs = new ArrayList<>();
        AgentContextCompressionProcessor processor = createProcessor(store,
            input -> input.getEstimatedPendingTokens() >= 100,
            value -> {
                inputs.add(value);
                return Collections.singletonList(summary("summary-" + (inputs.size())));
            }, messages -> messages.size());

        List<Message> first = messages(100);
        List<Message> second = new ArrayList<>(first);
        second.addAll(messages(100));
        List<Message> third = new ArrayList<>(second);
        third.addAll(messages(100));

        AgentContextCompressionResult r1 = processor.process("c1", first);
        AgentContextCompressionResult r2 = processor.process("c1", second);
        AgentContextCompressionResult r3 = processor.process("c1", third);

        assertTrue(r1.isCompressed());
        assertTrue(r2.isCompressed());
        assertTrue(r3.isCompressed());
        assertEquals(100, inputs.get(0).size());
        assertEquals(101, inputs.get(1).size()); // prior one-message summary + 100 new messages
        assertEquals(101, inputs.get(2).size());
        assertEquals(3L, r3.getState().getVersion());
        assertEquals(third.get(299).getMessageId(), r3.getState().getCoveredUntilMessageId());
        assertEquals("summary-3", r3.getModelMessages().get(0).getTextContent());
    }

    @Test
    public void shouldNotSaveWhenNoNewMessagesRemain() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> Collections.singletonList(summary("summary")), messages -> messages.size());
        List<Message> history = messages(2);
        processor.process("c1", history);
        AgentContextCompressionResult result = processor.process("c1", history);

        assertFalse(result.isCompressed());
        assertEquals(1, store.saves.size());
        assertEquals(1, result.getModelMessages().size());
    }

    @Test
    public void shouldExposeConditionInputAndCarryPreviousState() {
        MemoryStore store = new MemoryStore();
        AtomicReference<List<Message>> seenPending = new AtomicReference<>();
        AtomicReference<List<Message>> seenSummary = new AtomicReference<>();
        AtomicReference<AgentContextCompressionState> seenState = new AtomicReference<>();
        AtomicReference<Long> seenTokens = new AtomicReference<>();
        AtomicReference<Integer> seenTurns = new AtomicReference<>();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> {
            seenPending.set(input.getPendingMessages());
            seenSummary.set(input.getSummaryMessages());
            seenState.set(input.getState());
            seenTokens.set(input.getEstimatedPendingTokens());
            seenTurns.set(input.getPendingTurnCount());
            try {
                input.getPendingMessages().clear();
                fail("trigger input must be immutable");
            } catch (UnsupportedOperationException expected) {
                // expected
            }
            try {
                input.getSummaryMessages().clear();
                fail("summary input must be immutable");
            } catch (UnsupportedOperationException expected) {
                // expected
            }
            return input.getState().getVersion() == 0;
        }, value -> Collections.singletonList(summary("summary")), messages -> messages.size() * 7L);
        processor.process("c1", messages(2));

        assertNotNull(seenPending.get());
        assertNotNull(seenSummary.get());
        assertEquals(0L, seenState.get().getVersion());
        assertEquals(2, seenPending.get().size());
        assertEquals(2, seenTurns.get().intValue());
        assertEquals(7L * 2L, seenTokens.get().longValue());
    }

    @Test
    public void shouldRejectHistoryThatDoesNotContainCoveredMessage() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> Collections.singletonList(summary("summary")), messages -> messages.size());
        processor.process("c1", messages(2));
        try {
            processor.process("c1", messages(1));
            fail("expected stale history failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("not present"));
        }
        assertEquals(1, store.saves.size());
    }

    @Test
    public void shouldFailWithoutOverwritingStateOnCasConflict() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> Collections.singletonList(summary("summary")), messages -> messages.size());
        store.rejectNextSave = true;
        try {
            processor.process("c1", messages(1));
            fail("expected CAS failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("concurrently"));
        }
        assertNull(store.state);
    }

    @Test
    public void shouldRejectEmptyCompressorResultAndPreserveState() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> Collections.emptyList(), messages -> messages.size());
        try {
            processor.process("c1", messages(1));
            fail("expected empty summary failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("no summary"));
        }
        assertEquals(0, store.saves.size());
    }

    @Test
    public void shouldUseOriginalHistoryWhenIncrementalCompressorFails() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> {
                throw new IllegalStateException("compressor unavailable");
            }, messages -> messages.size(),
            AgentCompressionFailureStrategy.USE_ORIGINAL);
        List<Message> history = messages(2);

        AgentContextCompressionResult result = processor.process("fallback", history);

        assertFalse(result.isCompressed());
        assertEquals(history.size(), result.getModelMessages().size());
        assertEquals(0, store.saves.size());
        assertNull(store.state);
    }

    @Test
    public void shouldUseOriginalHistoryWhenCompressorReturnsEmptySummary() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> Collections.emptyList(), messages -> messages.size(),
            AgentCompressionFailureStrategy.USE_ORIGINAL);
        List<Message> history = messages(2);

        AgentContextCompressionResult result = processor.process("empty-fallback", history);

        assertFalse(result.isCompressed());
        assertEquals(history.size(), result.getModelMessages().size());
        assertEquals(0, store.saves.size());
        assertEquals(0L, result.getState().getVersion());
    }

    @Test
    public void shouldPropagateIncrementalCompressorFailureWithFailStrategy() {
        AgentContextCompressionProcessor processor = createProcessor(new MemoryStore(), input -> true,
            value -> {
                throw new IllegalStateException("compressor unavailable");
            }, messages -> messages.size(),
            AgentCompressionFailureStrategy.FAIL);
        try {
            processor.process("fail", messages(1));
            fail("expected compressor failure");
        } catch (IllegalStateException ex) {
            assertEquals("compressor unavailable", ex.getMessage());
        }
    }

    @Test
    public void shouldNotFallbackWhenCasSaveFails() {
        MemoryStore store = new MemoryStore();
        store.rejectNextSave = true;
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> Collections.singletonList(summary("summary")), messages -> messages.size(),
            AgentCompressionFailureStrategy.USE_ORIGINAL);
        try {
            processor.process("cas", messages(1));
            fail("expected CAS failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("concurrently"));
        }
        assertEquals(1, store.saves.size());
    }

    @Test
    public void shouldKeepStateAndInputsImmutable() {
        MemoryStore store = new MemoryStore();
        List<Message> history = messages(2);
        AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
            value -> Collections.singletonList(summary("summary")), messages -> messages.size());
        AgentContextCompressionResult result = processor.process("c1", history);
        history.clear();
        assertEquals(1, result.getState().getSummaryMessages().size());
        try {
            result.getState().getSummaryMessages().add(new AiMessage("x"));
            fail("expected immutable summary list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            result.getModelMessages().clear();
            fail("expected immutable model message list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void shouldRejectOrphanToolMessageAndUseOriginalWithoutAdvancingCursor() {
        List<Message> history = Arrays.<Message>asList(new UserMessage("question"), toolResult("missing"));
        for (AgentCompressionFailureStrategy strategy : new AgentCompressionFailureStrategy[]{
            AgentCompressionFailureStrategy.FAIL, AgentCompressionFailureStrategy.USE_ORIGINAL}) {
            MemoryStore store = new MemoryStore();
            AgentContextCompressionProcessor processor = createProcessor(store, input -> true,
                value -> Collections.<Message>singletonList(toolResult("missing")),
                messages -> messages.size(), strategy);
            try {
                AgentContextCompressionResult result = processor.process("orphan-" + strategy, history);
                assertEquals(AgentCompressionFailureStrategy.USE_ORIGINAL, strategy);
                assertFalse(result.isCompressed());
                assertEquals(history.size(), result.getModelMessages().size());
            } catch (IllegalArgumentException expected) {
                assertEquals(AgentCompressionFailureStrategy.FAIL, strategy);
            }
            assertEquals("invalid output must never be persisted", 0, store.saves.size());
            assertNull(store.state);
        }
    }

    @Test
    public void shouldRejectIncompleteAndDuplicateToolCallIds() {
        List<Message> incomplete = Arrays.<Message>asList(new UserMessage("question"), toolCalls("call-1"));
        List<Message> duplicate = Arrays.<Message>asList(new UserMessage("question"), toolCalls("same", "same"));
        List<Message> blank = Arrays.<Message>asList(new UserMessage("question"), toolCalls(" "));
        List<Message> duplicateAcrossGroups = Arrays.<Message>asList(new UserMessage("question"),
            toolCalls("same"), toolResult("same"), new UserMessage("next"),
            toolCalls("same"), toolResult("same"));
        for (List<Message> invalid : Arrays.asList(incomplete, duplicate, blank, duplicateAcrossGroups)) {
            AgentContextCompressionProcessor processor = createProcessor(new MemoryStore(), input -> true,
                value -> invalid, messages -> messages.size(), AgentCompressionFailureStrategy.FAIL);
            try {
                processor.process("invalid-tool", Arrays.<Message>asList(new UserMessage("question")));
                fail("invalid ToolCall protocol must fail");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("ToolCall"));
            }
        }
    }

    @Test
    public void shouldHandleZeroAndLargeTokenEstimatesAtConditionBoundary() {
        MemoryStore zeroStore = new MemoryStore();
        AtomicInteger zeroCalls = new AtomicInteger();
        AgentContextCompressionProcessor zero = createProcessor(zeroStore,
            input -> input.getEstimatedPendingTokens() > 0,
            value -> {
                zeroCalls.incrementAndGet();
                return Collections.singletonList(summary("x"));
            }, messages -> 0);
        assertFalse(zero.process("zero", messages(5)).isCompressed());
        assertEquals(0, zeroCalls.get());

        MemoryStore largeStore = new MemoryStore();
        AgentContextCompressionProcessor large = createProcessor(largeStore,
            input -> input.getEstimatedPendingTokens() == Long.MAX_VALUE,
            value -> Collections.singletonList(summary("large")), messages -> Long.MAX_VALUE);
        assertTrue(large.process("large", messages(1)).isCompressed());
    }

    @Test
    public void shouldRejectNegativeTokenEstimate() {
        AgentContextCompressionProcessor processor = createProcessor(new MemoryStore(), input -> true,
            value -> Collections.singletonList(summary("summary")), messages -> -1);
        try {
            processor.process("negative", messages(1));
            fail("expected invalid token estimate");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("non-negative"));
        }
    }

    @Test
    public void shouldRejectBlankConversationAndNullHistoryEntries() {
        AgentContextCompressionProcessor processor = createProcessor(new MemoryStore(), input -> false,
            value -> Collections.singletonList(summary("summary")), messages -> messages.size());
        try {
            processor.process("  ", messages(1));
            fail("blank conversation id must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("conversationId"));
        }
        List<Message> invalid = new ArrayList<>();
        invalid.add(null);
        try {
            processor.process("null-message", invalid);
            fail("null history message must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null messages"));
        }
    }

    private static AgentContextCompressionProcessor createProcessor(AgentContextCompressionStateStore store,
                                                                    AgentContextCompressionCondition condition,
                                                                    AgentContextCompressor compressor,
                                                                    ToLongFunction<List<Message>> estimator) {
        return new AgentContextCompressionProcessor(store, condition, compressor, estimator);
    }

    private static AgentContextCompressionProcessor createProcessor(AgentContextCompressionStateStore store,
                                                                    AgentContextCompressionCondition condition,
                                                                    AgentContextCompressor compressor,
                                                                    ToLongFunction<List<Message>> estimator,
                                                                    AgentCompressionFailureStrategy strategy) {
        return new AgentContextCompressionProcessor(store, condition, compressor, estimator, strategy);
    }

    private static List<Message> messages(int count) {
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new UserMessage("message-" + i));
        return result;
    }

    private static ToolMessage toolResult(String id) {
        ToolMessage result = new ToolMessage();
        result.setToolCallId(id);
        result.setContent("result");
        return result;
    }

    private static UserMessage summary(String text) {
        return new UserMessage(text);
    }

    private static AiMessage toolCalls(String... ids) {
        AiMessage result = new AiMessage();
        List<ToolCall> calls = new ArrayList<>();
        for (String id : ids) calls.add(new ToolCall(id, "lookup", "{}"));
        result.setToolCalls(calls);
        return result;
    }

    private static final class MemoryStore implements AgentContextCompressionStateStore {
        private AgentContextCompressionState state;
        private boolean rejectNextSave;
        private final List<Save> saves = new ArrayList<>();

        @Override
        public AgentContextCompressionState load(String conversationId) {
            return state;
        }

        @Override
        public boolean save(String conversationId, AgentContextCompressionState next, long expectedVersion) {
            saves.add(new Save(expectedVersion));
            if (rejectNextSave) {
                rejectNextSave = false;
                return false;
            }
            if (state != null && state.getVersion() != expectedVersion) return false;
            state = next;
            return true;
        }
    }

    private static final class Save {
        private final long expectedVersion;

        private Save(long expectedVersion) {
            this.expectedVersion = expectedVersion;
        }
    }
}
