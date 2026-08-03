package com.agentsflex.agent.event;

import com.agentsflex.agent.AgentRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 为每个 Run 分配单调序号并向进程内监听器发布实时事件。
 *
 * <p>监听器在发布线程中同步执行，因此需要快速返回；单个监听器异常会被记录并隔离，不影响 Run
 * 状态推进。序号只在当前 JVM 生命周期内有效，不可用于跨进程断点续读。</p>
 */
public final class AgentRuntimeEventStream {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeEventStream.class);
    /**
     * 支持发布期间并发添加和删除的监听器列表。
     */
    private final CopyOnWriteArrayList<AgentRuntimeEventListener> listeners = new CopyOnWriteArrayList<>();
    /**
     * 每个活动 Run 独立的进程内事件序号计数器。
     */
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    /**
     * 添加线程安全的实时事件监听器。
     */
    public AgentRuntimeEventStream addListener(AgentRuntimeEventListener listener) {
        if (listener != null) listeners.add(listener);
        return this;
    }

    /**
     * 删除已经注册的实时事件监听器。
     */
    public AgentRuntimeEventStream removeListener(AgentRuntimeEventListener listener) {
        listeners.remove(listener);
        return this;
    }

    /**
     * 为指定 Run 分配下一个进程内序号并同步通知全部监听器。
     */
    public AgentRuntimeEvent publish(AgentRun run, AgentRuntimeEventType type, Map<String, ?> data) {
        AtomicLong counter = sequences.computeIfAbsent(run.getId(), key -> new AtomicLong());
        long sequence = counter.incrementAndGet();
        AgentRuntimeEvent event = new AgentRuntimeEvent(run.getId(), run.getRootRunId(),
            run.getParentRunId(), run.getAgent().getId(), run.getAgent().getVersion(),
            sequence, type, data);
        for (AgentRuntimeEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException error) {
                log.warn("Agent runtime event listener failed", error);
            }
        }
        return event;
    }

    /**
     * Run 的全部同步事件发布完成后释放序号计数器。
     */
    public void release(String runId) {
        if (runId != null) sequences.remove(runId);
    }
}
