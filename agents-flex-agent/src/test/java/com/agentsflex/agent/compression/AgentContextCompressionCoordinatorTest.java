package com.agentsflex.agent.compression;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

import static org.junit.Assert.*;

/**
 * 增量压缩状态、触发边界和多轮 CAS 保存的综合测试。
 */
public class AgentContextCompressionCoordinatorTest {

    @Test
    public void shouldReturnPendingMessagesWithoutCompressingBeforeThreshold() {
        MemoryStore store = new MemoryStore();
        AtomicInteger compressions = new AtomicInteger();
        AgentContextCompressionCoordinator coordinator = coordinator(store,
            input -> input.getEstimatedPendingTokens() >= 4,
            input -> {
                compressions.incrementAndGet();
                return Collections.singletonList(new AiMessage("summary"));
            },
            messages -> messages.size());
        List<Message> history = messages(3);

        AgentContextCompressionResult result = coordinator.compress("c1", history);

        assertFalse(result.isCompressed());
        assertEquals(history.size(), result.getModelMessages().size());
        assertEquals(0, compressions.get());
        assertEquals(0, store.saves.size());
        assertEquals(0, result.getState().getCompressionVersion());
    }

    @Test
    public void shouldPersistInitialCompressionWithVersionZeroCas() {
        MemoryStore store = new MemoryStore();
        AtomicReference<List<Message>> input = new AtomicReference<>();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> true,
            value -> {
                input.set(value);
                return Collections.singletonList(new AiMessage("summary-1"));
            },
            messages -> messages.size());
        List<Message> history = messages(100);

        AgentContextCompressionResult result = coordinator.compress("c1", history);

        assertTrue(result.isCompressed());
        assertEquals(100, input.get().size());
        assertEquals(0L, store.saves.get(0).expectedVersion);
        assertEquals(1L, result.getState().getVersion());
        assertEquals(1L, result.getState().getCompressionVersion());
        assertEquals(history.get(99).getMessageId(), result.getState().getCoveredUntilMessageId());
        assertEquals(100, result.getState().getCoveredTurnCount());
        assertEquals(1, result.getModelMessages().size());
    }

    @Test
    public void shouldCompressOnlyNewMessagesAcrossMultipleRounds() {
        MemoryStore store = new MemoryStore();
        List<List<Message>> inputs = new ArrayList<>();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> value.getEstimatedPendingTokens() >= 100,
            value -> {
                inputs.add(value);
                return Collections.singletonList(new AiMessage("summary-" + (inputs.size())));
            }, messages -> messages.size());

        List<Message> first = messages(100);
        List<Message> second = new ArrayList<>(first);
        second.addAll(messages(100));
        List<Message> third = new ArrayList<>(second);
        third.addAll(messages(100));

        AgentContextCompressionResult r1 = coordinator.compress("c1", first);
        AgentContextCompressionResult r2 = coordinator.compress("c1", second);
        AgentContextCompressionResult r3 = coordinator.compress("c1", third);

        assertTrue(r1.isCompressed());
        assertTrue(r2.isCompressed());
        assertTrue(r3.isCompressed());
        assertEquals(100, inputs.get(0).size());
        assertEquals(101, inputs.get(1).size()); // prior one-message summary + 100 new messages
        assertEquals(101, inputs.get(2).size());
        assertEquals(3L, r3.getState().getVersion());
        assertEquals(3L, r3.getState().getCompressionVersion());
        assertEquals(300, r3.getState().getCoveredTurnCount());
        assertEquals(300L, r3.getState().getEstimatedCoveredTokens());
        assertEquals(third.get(299).getMessageId(), r3.getState().getCoveredUntilMessageId());
        assertEquals("summary-3", r3.getModelMessages().get(0).getTextContent());
    }

    @Test
    public void shouldNotSaveWhenNoNewMessagesRemain() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> true,
            value -> Collections.singletonList(new AiMessage("summary")), messages -> messages.size());
        List<Message> history = messages(2);
        coordinator.compress("c1", history);
        AgentContextCompressionResult result = coordinator.compress("c1", history);

        assertFalse(result.isCompressed());
        assertEquals(1, store.saves.size());
        assertEquals(1, result.getModelMessages().size());
    }

    @Test
    public void shouldExposeTriggerInputAndCarryPreviousState() {
        MemoryStore store = new MemoryStore();
        AtomicReference<AgentContextCompressionInput> seen = new AtomicReference<>();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> {
            seen.set(value);
            return value.getState().getCompressionVersion() == 0;
        }, value -> Collections.singletonList(new AiMessage("summary")), messages -> messages.size() * 7L);
        coordinator.compress("c1", messages(2));

        assertNotNull(seen.get());
        assertEquals(0L, seen.get().getState().getCompressionVersion());
        assertEquals(2, seen.get().getPendingTurnCount());
        assertEquals(7L * 2L, seen.get().getEstimatedPendingTokens());
    }

    @Test
    public void shouldRejectHistoryThatDoesNotContainCoveredMessage() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> true,
            value -> Collections.singletonList(new AiMessage("summary")), messages -> messages.size());
        coordinator.compress("c1", messages(2));
        try {
            coordinator.compress("c1", messages(1));
            fail("expected stale history failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("not present"));
        }
        assertEquals(1, store.saves.size());
    }

    @Test
    public void shouldFailWithoutOverwritingStateOnCasConflict() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> true,
            value -> Collections.singletonList(new AiMessage("summary")), messages -> messages.size());
        store.rejectNextSave = true;
        try {
            coordinator.compress("c1", messages(1));
            fail("expected CAS failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("concurrently"));
        }
        assertNull(store.state);
    }

    @Test
    public void shouldRejectEmptyCompressorResultAndPreserveState() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> true,
            value -> Collections.emptyList(), messages -> messages.size());
        try {
            coordinator.compress("c1", messages(1));
            fail("expected empty summary failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("no summary"));
        }
        assertEquals(0, store.saves.size());
    }

    @Test
    public void shouldKeepStateAndInputsImmutable() {
        MemoryStore store = new MemoryStore();
        List<Message> history = messages(2);
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> true,
            value -> Collections.singletonList(new AiMessage("summary")), messages -> messages.size());
        AgentContextCompressionResult result = coordinator.compress("c1", history);
        history.clear();
        assertEquals(1, result.getState().getSummaryMessages().size());
        try {
            result.getState().getSummaryMessages().add(new AiMessage("x"));
            fail("expected immutable summary list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        assertEquals(2, result.getState().getCoveredTurnCount());
    }

    @Test
    public void shouldHandleZeroAndLargeTokenEstimatesAtTriggerBoundary() {
        MemoryStore zeroStore = new MemoryStore();
        AtomicInteger zeroCalls = new AtomicInteger();
        AgentContextCompressionCoordinator zero = coordinator(zeroStore, value -> value.getEstimatedPendingTokens() > 0,
            value -> {
                zeroCalls.incrementAndGet();
                return Collections.singletonList(new AiMessage("x"));
            }, messages -> 0);
        assertFalse(zero.compress("zero", messages(5)).isCompressed());
        assertEquals(0, zeroCalls.get());

        MemoryStore largeStore = new MemoryStore();
        AgentContextCompressionCoordinator large = coordinator(largeStore, value -> value.getEstimatedPendingTokens() == Long.MAX_VALUE,
            value -> Collections.singletonList(new AiMessage("large")), messages -> Long.MAX_VALUE);
        assertTrue(large.compress("large", messages(1)).isCompressed());
        assertEquals(Long.MAX_VALUE, largeStore.state.getEstimatedCoveredTokens());
    }

    @Test
    public void shouldSaturateTokenAndTurnCountersInsteadOfOverflowing() {
        MemoryStore store = new MemoryStore();
        AgentContextCompressionCoordinator coordinator = coordinator(store, value -> true,
            value -> Collections.singletonList(new AiMessage("summary")), messages -> Long.MAX_VALUE);
        List<Message> first = messages(1);
        coordinator.compress("c1", first);
        List<Message> next = new ArrayList<>(first);
        next.addAll(messages(1));
        AgentContextCompressionResult result = coordinator.compress("c1", next);
        assertEquals(Long.MAX_VALUE, result.getState().getEstimatedCoveredTokens());
        assertEquals(2, result.getState().getCoveredTurnCount());
    }

    @Test
    public void shouldRejectNegativeTokenEstimate() {
        AgentContextCompressionCoordinator coordinator = coordinator(new MemoryStore(), value -> true,
            value -> Collections.singletonList(new AiMessage("summary")), messages -> -1);
        try {
            coordinator.compress("negative", messages(1));
            fail("expected invalid token estimate");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("non-negative"));
        }
    }

    private static AgentContextCompressionCoordinator coordinator(AgentContextCompressionStateStore store,
                                                                  AgentContextCompressionTrigger trigger,
                                                                  AgentContextCompressor compressor,
                                                                  ToLongFunction<List<Message>> estimator) {
        return new AgentContextCompressionCoordinator(store, trigger, compressor, estimator);
    }

    private static List<Message> messages(int count) {
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new UserMessage("message-" + i));
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
