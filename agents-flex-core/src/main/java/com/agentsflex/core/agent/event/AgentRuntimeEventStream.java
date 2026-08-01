package com.agentsflex.core.agent.event;

import com.agentsflex.core.agent.AgentRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 为每个 Run 分配单调序号并向进程内监听器发布实时事件。 */
public final class AgentRuntimeEventStream {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeEventStream.class);
    private final CopyOnWriteArrayList<AgentRuntimeEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    /** 添加线程安全的实时事件监听器。 */
    public AgentRuntimeEventStream addListener(AgentRuntimeEventListener listener) {
        if (listener != null) listeners.add(listener);
        return this;
    }

    /** 删除已经注册的实时事件监听器。 */
    public AgentRuntimeEventStream removeListener(AgentRuntimeEventListener listener) {
        listeners.remove(listener);
        return this;
    }

    /** 为指定 Run 分配下一个进程内序号并同步通知全部监听器。 */
    public AgentRuntimeEvent publish(AgentRun run, AgentRuntimeEventType type,
                                     Map<String, ?> data) {
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

    /** Run 的全部同步事件发布完成后释放序号计数器。 */
    public void release(String runId) {
        if (runId != null) sequences.remove(runId);
    }
}
