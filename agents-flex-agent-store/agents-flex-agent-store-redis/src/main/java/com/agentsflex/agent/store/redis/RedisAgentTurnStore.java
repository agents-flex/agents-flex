package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.store.AgentTurnStore;
import com.agentsflex.agent.store.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.ParentChildTurnSnapshots;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 使用 Redis Hash 与 Lua 脚本实现跨进程 AgentTurn CAS、取消和 Lease。
 */
public final class RedisAgentTurnStore extends RedisAgentStoreSupport implements AgentTurnStore {
    private static final String SAVE = "local a=redis.call('HGET',KEYS[1],'version'); local actual=a and tonumber(a) or -1; "
        + "if actual~=tonumber(ARGV[1]) then return actual end; local cancel=redis.call('HGET',KEYS[1],'cancel'); "
        + "local c=(cancel=='1' or ARGV[9]=='1') and '1' or '0'; redis.call('HSET',KEYS[1],"
        + "'version',ARGV[2],'status',ARGV[3],'next',ARGV[4],'lease_owner',ARGV[5],'lease_id',ARGV[6],'lease_until',ARGV[7],"
        + "'parent',ARGV[8],'cancel',c,'payload',ARGV[10]); redis.call('SADD',KEYS[2],ARGV[11]); "
        + "local s=ARGV[3]; local terminal=(s=='COMPLETED' or s=='FAILED' or s=='CANCELLED' or s=='MAX_ITERATIONS_REACHED' or s=='MAX_STEPS_REACHED' or s=='BUDGET_EXCEEDED'); "
        + "local score=nil; local lu=tonumber(ARGV[7]); if (c=='1' and not terminal) or s=='READY' or s=='RUNNING' then "
        + "score=(ARGV[5]~='' and lu or 0) elseif s=='RETRY_SCHEDULED' then score=math.max(tonumber(ARGV[4]),lu) end; "
        + "if score then redis.call('ZADD',KEYS[3],score,ARGV[11]) else redis.call('ZREM',KEYS[3],ARGV[11]) end; return -2";
    private static final String CLAIM = "local status=redis.call('HGET',KEYS[1],'status'); if not status then return 0 end; "
        + "local nextRun=tonumber(redis.call('HGET',KEYS[1],'next') or '0'); local leaseUntil=tonumber(redis.call('HGET',KEYS[1],'lease_until') or '0'); "
        + "local cancel=redis.call('HGET',KEYS[1],'cancel')=='1'; local terminal=(status=='COMPLETED' or status=='FAILED' or status=='CANCELLED' or status=='MAX_ITERATIONS_REACHED' or status=='MAX_STEPS_REACHED' or status=='BUDGET_EXCEEDED'); "
        + "local runnable=(status=='READY' or status=='RUNNING' or (cancel and not terminal) "
        + "or (status=='RETRY_SCHEDULED' and nextRun<=tonumber(ARGV[1]))); if not runnable then redis.call('ZREM',KEYS[3],ARGV[6]); return 0 end; "
        + "if leaseUntil>tonumber(ARGV[1]) then redis.call('ZADD',KEYS[3],leaseUntil,ARGV[6]); return 0 end; "
        + "if ARGV[5]=='1' and redis.call('EXISTS',KEYS[2])==1 then local pu=tonumber(redis.call('HGET',KEYS[2],'lease_until') or '0'); "
        + "local po=redis.call('HGET',KEYS[2],'lease_owner'); if po and po~='' and pu>tonumber(ARGV[1]) then return 0 end end; "
        + "redis.call('HSET',KEYS[1],'lease_owner',ARGV[2],'lease_id',ARGV[4],'lease_until',ARGV[3]); "
        + "redis.call('HINCRBY',KEYS[1],'version',1); redis.call('ZADD',KEYS[3],ARGV[3],ARGV[6]); return 1";

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
            .status(AgentTurnStatus.valueOf(values.get("status"))).nextRunnableAt(number(values.get("next")))
            .leaseOwner(emptyToNull(values.get("lease_owner"))).leaseId(emptyToNull(values.get("lease_id")))
            .leaseUntil(number(values.get("lease_until")))
            .parentTurnId(emptyToNull(values.get("parent")))
            .cancellationRequested("1".equals(values.get("cancel"))).build();
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
            keys(key("turn", state.getTurnId()), index("turns"), index("runnable-turns")), args(
                String.valueOf(expectedVersion), String.valueOf(state.getVersion()), state.getStatus().name(),
                String.valueOf(state.getNextRunnableAt()), text(state.getLeaseOwner()), text(state.getLeaseId()),
                String.valueOf(state.getLeaseUntil()), text(state.getParentTurnId()),
                state.isCancellationRequested() ? "1" : "0", encode(saved), state.getTurnId()));
        long code = ((Number) result).longValue();
        if (code != -2) {
            throw new AgentTurnVersionConflictException(state.getTurnId(), expectedVersion, code);
        }
        return load(state.getTurnId());
    }

    @Override
    public boolean requestCancellation(String turnId) {
        String script = "local status=redis.call('HGET',KEYS[1],'status'); if not status then return -1 end; "
            + "if redis.call('HGET',KEYS[1],'cancel')=='1' then return 0 end; "
            + "if status=='COMPLETED' or status=='FAILED' or status=='CANCELLED' or status=='MAX_ITERATIONS_REACHED' "
            + "or status=='MAX_STEPS_REACHED' or status=='BUDGET_EXCEEDED' then return 0 end; "
            + "redis.call('HSET',KEYS[1],'cancel','1'); redis.call('ZADD',KEYS[2],0,ARGV[1]); return 1";
        long result = ((Number) eval(script, keys(key("turn", turnId), index("runnable-turns")), args(turnId))).longValue();
        if (result == -1) throw new IllegalStateException("AgentTurn snapshot not found: " + turnId);
        return result == 1;
    }

    @Override
    public ParentChildTurnSnapshots saveParentAndChild(AgentTurnSnapshot parent, long expectedParentVersion, AgentTurnSnapshot child) {
        if (parent == null || child == null)
            throw new IllegalArgumentException("parent and child snapshots must not be null");
        AgentTurnSnapshot savedParent = parent.withVersion(expectedParentVersion + 1);
        AgentTurnSnapshot savedChild = child.withVersion(0);
        AgentTurnState parentState = savedParent.getState();
        AgentTurnState childState = savedChild.getState();
        String script = "local pv=redis.call('HGET',KEYS[1],'version'); local actual=pv and tonumber(pv) or -1; "
            + "if actual~=tonumber(ARGV[1]) then return actual end; if redis.call('EXISTS',KEYS[2])==1 then return -3 end; "
            + "local pc=redis.call('HGET',KEYS[1],'cancel'); local c=(pc=='1' or ARGV[9]=='1') and '1' or '0'; "
            + "redis.call('HSET',KEYS[1],'version',ARGV[2],'status',ARGV[3],'next',ARGV[4],'lease_owner',ARGV[5],"
            + "'lease_id',ARGV[6],'lease_until',ARGV[7],'parent',ARGV[8],'cancel',c,'payload',ARGV[10]); "
            + "redis.call('HSET',KEYS[2],'version','0','status',ARGV[11],'next',ARGV[12],'lease_owner',ARGV[13],"
            + "'lease_id',ARGV[14],'lease_until',ARGV[15],'parent',ARGV[16],'cancel',ARGV[17],'payload',ARGV[18]); "
            + "redis.call('SADD',KEYS[3],ARGV[19],ARGV[20]); redis.call('ZREM',KEYS[4],ARGV[19]); "
            + "local cs=ARGV[11]; if cs=='READY' or cs=='RUNNING' then redis.call('ZADD',KEYS[4],ARGV[15],ARGV[20]) "
            + "elseif cs=='RETRY_SCHEDULED' then redis.call('ZADD',KEYS[4],math.max(tonumber(ARGV[12]),tonumber(ARGV[15])),ARGV[20]) end; return -2";
        Object result = eval(script,
            keys(key("turn", parentState.getTurnId()), key("turn", childState.getTurnId()),
                index("turns"), index("runnable-turns")), args(
                String.valueOf(expectedParentVersion), String.valueOf(parentState.getVersion()),
                parentState.getStatus().name(), String.valueOf(parentState.getNextRunnableAt()),
                text(parentState.getLeaseOwner()), text(parentState.getLeaseId()),
                String.valueOf(parentState.getLeaseUntil()), text(parentState.getParentTurnId()),
                parentState.isCancellationRequested() ? "1" : "0", encode(savedParent),
                childState.getStatus().name(), String.valueOf(childState.getNextRunnableAt()),
                text(childState.getLeaseOwner()), text(childState.getLeaseId()),
                String.valueOf(childState.getLeaseUntil()), text(childState.getParentTurnId()),
                childState.isCancellationRequested() ? "1" : "0", encode(savedChild),
                parentState.getTurnId(), childState.getTurnId()));
        long code = ((Number) result).longValue();
        if (code == -3) {
            throw new AgentTurnVersionConflictException(childState.getTurnId(), -1,
                number(jedis.hget(key("turn", childState.getTurnId()), "version")));
        }
        if (code != -2) {
            throw new AgentTurnVersionConflictException(parentState.getTurnId(),
                expectedParentVersion, code);
        }
        return new ParentChildTurnSnapshots(load(parentState.getTurnId()),
            load(childState.getTurnId()));
    }

    @Override
    public List<AgentTurnSnapshot> claimRunnable(String workerId, long now, long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0)
            throw new IllegalArgumentException("invalid lease request");
        List<AgentTurnSnapshot> result = new ArrayList<>();
        List<String> ids = jedis.zrangeByScore(index("runnable-turns"), Double.NEGATIVE_INFINITY,
            now, 0, Math.max(limit * 8, limit));
        for (String id : ids) {
            if (result.size() >= limit) break;
            String parent = jedis.hget(key("turn", id), "parent");
            boolean hasParent = parent != null && !parent.isEmpty();
            String parentKey = hasParent ? key("turn", parent) : key("turn", id);
            long claimed = ((Number) eval(CLAIM, keys(key("turn", id), parentKey, index("runnable-turns")), args(
                String.valueOf(now), workerId, String.valueOf(now + leaseMillis),
                UUID.randomUUID().toString(), hasParent ? "1" : "0", id))).longValue();
            if (claimed == 1) result.add(load(id));
        }
        return result;
    }

    @Override
    public AgentTurnSnapshot renewLease(String turnId, String workerId, String leaseId,
                                        long now, long leaseUntil) {
        if (leaseUntil <= now) throw new IllegalArgumentException("leaseUntil must be after now");
        String script = "if redis.call('HGET',KEYS[1],'lease_owner')~=ARGV[1] "
            + "or redis.call('HGET',KEYS[1],'lease_id')~=ARGV[2] "
            + "or tonumber(redis.call('HGET',KEYS[1],'lease_until') or '0')<=tonumber(ARGV[3]) then return 0 end; "
            + "redis.call('HSET',KEYS[1],'lease_until',ARGV[4]); redis.call('ZADD',KEYS[2],ARGV[4],ARGV[5]); return 1";
        if (((Number) eval(script, keys(key("turn", turnId), index("runnable-turns")), args(workerId, leaseId,
            String.valueOf(now), String.valueOf(leaseUntil), turnId))).longValue() != 1)
            throw new IllegalStateException("AgentTurn lease is not owned by worker: " + workerId);
        return load(turnId);
    }

    @Override
    public void releaseLease(String turnId, String workerId, String leaseId) {
        String script = "if redis.call('HGET',KEYS[1],'lease_owner')==ARGV[1] "
            + "and redis.call('HGET',KEYS[1],'lease_id')==ARGV[2] then redis.call('HSET',KEYS[1],"
            + "'lease_owner','','lease_id','','lease_until','0'); local s=redis.call('HGET',KEYS[1],'status'); "
            + "local c=redis.call('HGET',KEYS[1],'cancel')=='1'; local terminal=(s=='COMPLETED' or s=='FAILED' or s=='CANCELLED' or s=='MAX_ITERATIONS_REACHED' or s=='MAX_STEPS_REACHED' or s=='BUDGET_EXCEEDED'); "
            + "if (c and not terminal) or s=='READY' or s=='RUNNING' then redis.call('ZADD',KEYS[2],0,ARGV[3]) "
            + "elseif s=='RETRY_SCHEDULED' then redis.call('ZADD',KEYS[2],redis.call('HGET',KEYS[1],'next'),ARGV[3]) "
            + "else redis.call('ZREM',KEYS[2],ARGV[3]) end; return 1 end; return 0";
        eval(script, keys(key("turn", turnId), index("runnable-turns")), args(workerId, leaseId, turnId));
    }

    @Override
    public List<AgentTurnSnapshot> findTerminalChildrenWithWaitingParent(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be greater than 0");
        List<AgentTurnSnapshot> result = new ArrayList<>();
        for (String id : jedis.smembers(index("turns"))) {
            if (result.size() >= limit) break;
            AgentTurnSnapshot child = load(id);
            if (child == null) continue;
            AgentTurnState childState = child.getState();
            if (!childState.getStatus().isTerminal() || childState.getParentTurnId() == null) continue;
            AgentTurnSnapshot parent = load(childState.getParentTurnId());
            AgentTurnState parentState = parent == null ? null : parent.getState();
            if (parentState != null
                && parentState.getStatus() == AgentTurnStatus.WAITING_FOR_CHILD
                && parentState.getSuspension() != null
                && childState.getTurnId().equals(parentState.getSuspension().getCorrelationId())) {
                result.add(child);
            }
        }
        return result;
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

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
