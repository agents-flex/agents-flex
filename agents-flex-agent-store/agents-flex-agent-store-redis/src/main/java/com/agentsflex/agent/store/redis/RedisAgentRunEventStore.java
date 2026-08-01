package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.event.AgentRunEvent;
import com.agentsflex.agent.event.AgentRunEventStore;

import java.util.ArrayList;
import java.util.List;

/** 使用 Redis 自增序号和有序集合保存幂等 AgentRun 事件流。 */
public final class RedisAgentRunEventStore extends RedisAgentStoreSupport implements AgentRunEventStore {
    RedisAgentRunEventStore(RedisAgentStoreConfig config) { super(config); }

    @Override
    public AgentRunEvent append(AgentRunEvent event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        String script = "local old=redis.call('HGET',KEYS[1],'sequence'); if old then return tonumber(old) end; "
            + "local seq=redis.call('INCR',KEYS[2]); redis.call('HSET',KEYS[1],'sequence',seq,'payload',ARGV[1]); "
            + "redis.call('ZADD',KEYS[3],seq,ARGV[2]); return seq";
        long sequence = ((Number) eval(script, keys(key("event", event.getEventId()), key("event-sequence", event.getRunId()),
            key("events", event.getRunId())), args(encode(event), event.getEventId()))).longValue();
        return event.withSequence(sequence);
    }

    @Override
    public List<AgentRunEvent> load(String runId, long afterSequence, int limit) {
        if (runId == null || afterSequence < 0 || limit <= 0) throw new IllegalArgumentException("invalid event query");
        List<String> ids = jedis.zrangeByScore(key("events", runId), afterSequence + 1, Double.POSITIVE_INFINITY, 0, limit);
        List<AgentRunEvent> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            String eventKey = key("event", id);
            AgentRunEvent payload = decode(jedis.hget(eventKey, "payload"), AgentRunEvent.class);
            if (payload != null) result.add(payload.withSequence(Long.parseLong(jedis.hget(eventKey, "sequence"))));
        }
        return result;
    }
}
