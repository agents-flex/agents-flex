package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.AgentTurnStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 使用 Redis Hash、Lua 脚本和乐观锁保存 AgentTurn Snapshot。
 *
 * <p>AgentRunner 不会自动扫描或接管未完成 Turn，因此这里只维护 Snapshot CAS、取消标记和活动会话查询。</p>
 */
public final class RedisAgentTurnStore extends RedisAgentStoreSupport implements AgentTurnStore {

    private static final String SAVE =
        "local a=redis.call('HGET',KEYS[1],'version'); "
            + "local actual=a and tonumber(a) or -1; "
            + "if actual~=tonumber(ARGV[1]) then return actual end; "
            + "local cancel=redis.call('HGET',KEYS[1],'cancel'); "
            + "local c=(cancel=='1' or ARGV[7]=='1') and '1' or '0'; "
            + "redis.call('HSET',KEYS[1],'version',ARGV[2],'status',ARGV[3],"
            + "'next',ARGV[4],'cancel',c,'payload',ARGV[5]); "
            + "redis.call('SADD',KEYS[2],ARGV[6]); return -2";

    RedisAgentTurnStore(RedisAgentStoreConfig config) {
        super(config);
    }

    @Override
    public long currentTimeMillis() {
        @SuppressWarnings("unchecked")
        List<Object> time = (List<Object>) eval("return redis.call('TIME')", noKeys(), noKeys());
        return numberValue(time.get(0)) * 1000L + numberValue(time.get(1)) / 1000L;
    }

    @Override
    public AgentTurnSnapshot load(String turnId) {
        Map<String, String> values = jedis.hgetAll(key("turn", turnId));
        if (values == null || values.isEmpty()) return null;
        AgentTurnSnapshot payload = decode(values.get("payload"), AgentTurnSnapshot.class);
        AgentTurnState state = payload.getState().toBuilder()
            .version(Long.parseLong(values.get("version")))
            .status(AgentTurnStatus.valueOf(values.get("status")))
            .nextRunnableAt(number(values.get("next")))
            .cancellationRequested("1".equals(values.get("cancel")))
            .build();
        return payload.withState(state);
    }

    @Override
    public AgentTurnSnapshot findActiveTurn(String conversationId) {
        if (conversationId == null) return null;
        for (String id : jedis.smembers(index("turns"))) {
            AgentTurnSnapshot snapshot = load(id);
            if (snapshot == null || snapshot.getState().getStatus().isTerminal()) continue;
            Object value = snapshot.getState().getMetadata().get("agentsflex.conversationId");
            if (conversationId.equals(value)) return snapshot;
        }
        return null;
    }

    @Override
    public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        AgentTurnSnapshot saved = snapshot.withVersion(expectedVersion + 1);
        AgentTurnState state = saved.getState();
        Object result = eval(SAVE,
            keys(key("turn", state.getTurnId()), index("turns")),
            args(String.valueOf(expectedVersion), String.valueOf(state.getVersion()),
                state.getStatus().name(), String.valueOf(state.getNextRunnableAt()), encode(saved),
                state.getTurnId(), state.isCancellationRequested() ? "1" : "0"));
        long code = ((Number) result).longValue();
        if (code != -2) {
            throw new AgentTurnVersionConflictException(state.getTurnId(), expectedVersion, code);
        }
        return load(state.getTurnId());
    }

    @Override
    public boolean requestCancellation(String turnId) {
        String script = "local status=redis.call('HGET',KEYS[1],'status'); "
            + "if not status then return -1 end; "
            + "if redis.call('HGET',KEYS[1],'cancel')=='1' then return 0 end; "
            + "if status=='COMPLETED' or status=='FAILED' or status=='CANCELLED' "
            + "or status=='MAX_ITERATIONS_REACHED' or status=='MAX_STEPS_REACHED' "
            + "or status=='BUDGET_EXCEEDED' then return 0 end; "
            + "redis.call('HSET',KEYS[1],'cancel','1'); return 1";
        long result = ((Number) eval(script, keys(key("turn", turnId)), noKeys())).longValue();
        if (result == -1) throw new IllegalStateException("AgentTurn snapshot not found: " + turnId);
        return result == 1;
    }

    private static long number(String value) {
        return value == null || value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private static long numberValue(Object value) {
        if (value instanceof byte[]) {
            return Long.parseLong(new String((byte[]) value, StandardCharsets.US_ASCII));
        }
        return Long.parseLong(String.valueOf(value));
    }
}
