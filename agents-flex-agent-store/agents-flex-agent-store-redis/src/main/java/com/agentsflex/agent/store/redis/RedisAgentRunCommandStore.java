package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.command.AgentRunCommand;
import com.agentsflex.agent.command.AgentRunCommandStatus;
import com.agentsflex.agent.command.AgentRunCommandStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 使用 Redis Hash 与 Lua 脚本实现持久化命令收件箱。 */
public final class RedisAgentRunCommandStore extends RedisAgentStoreSupport implements AgentRunCommandStore {
    RedisAgentRunCommandStore(RedisAgentStoreConfig config) { super(config); }

    @Override
    public AgentRunCommand submit(AgentRunCommand command) {
        if (command == null) throw new IllegalArgumentException("command must not be null");
        String script = "if redis.call('EXISTS',KEYS[1])==1 then return 0 end; redis.call('HSET',KEYS[1],"
            + "'status',ARGV[1],'lease_owner',ARGV[2],'lease_until',ARGV[3],'attempts',ARGV[4],"
            + "'error',ARGV[5],'payload',ARGV[6]); redis.call('SADD',KEYS[2],ARGV[7]); return 1";
        long inserted = ((Number) eval(script, keys(key("command", command.getCommandId()), index("commands")), args(
            command.getStatus().name(), text(command.getLeaseOwner()), String.valueOf(command.getLeaseUntil()),
            String.valueOf(command.getAttempts()), text(command.getErrorMessage()), encode(command), command.getCommandId()))).longValue();
        if (inserted == 1) return command;
        AgentRunCommand existing = load(command.getCommandId());
        if (!sameCommand(existing, command)) throw new IllegalArgumentException(
            "commandId is already used by a different command: " + command.getCommandId());
        return existing;
    }

    @Override
    public AgentRunCommand load(String commandId) {
        Map<String, String> values = jedis.hgetAll(key("command", commandId));
        if (values == null || values.isEmpty()) return null;
        AgentRunCommand payload = decode(values.get("payload"), AgentRunCommand.class);
        return new AgentRunCommand(payload.getCommandId(), payload.getRunId(), payload.getCommand(), payload.getCreatedAt(),
            AgentRunCommandStatus.valueOf(values.get("status")), emptyToNull(values.get("lease_owner")),
            number(values.get("lease_until")), (int) number(values.get("attempts")), emptyToNull(values.get("error")));
    }

    @Override
    public List<AgentRunCommand> claim(String workerId, long now, long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0) throw new IllegalArgumentException("invalid command claim request");
        String script = "local s=redis.call('HGET',KEYS[1],'status'); local u=tonumber(redis.call('HGET',KEYS[1],'lease_until') or '0'); "
            + "if not (s=='PENDING' or (s=='CLAIMED' and u<=tonumber(ARGV[1]))) then return 0 end; "
            + "redis.call('HSET',KEYS[1],'status','CLAIMED','lease_owner',ARGV[2],'lease_until',ARGV[3],'error',''); "
            + "redis.call('HINCRBY',KEYS[1],'attempts',1); return 1";
        List<AgentRunCommand> result = new ArrayList<>(); Set<String> ids = jedis.smembers(index("commands"));
        for (String id : ids) {
            if (result.size() >= limit) break;
            if (((Number) eval(script, keys(key("command", id)), args(String.valueOf(now), workerId,
                String.valueOf(now + leaseMillis)))).longValue() == 1) result.add(load(id));
        }
        return result;
    }

    @Override public void acknowledge(String commandId, String workerId) { update(commandId, workerId, "COMPLETED", null); }
    @Override public void release(String commandId, String workerId, String errorMessage) { update(commandId, workerId, "PENDING", errorMessage); }
    @Override public void fail(String commandId, String workerId, String errorMessage) { update(commandId, workerId, "FAILED", errorMessage); }

    private void update(String commandId, String workerId, String status, String error) {
        String script = "if redis.call('HGET',KEYS[1],'status')~='CLAIMED' or redis.call('HGET',KEYS[1],'lease_owner')~=ARGV[1] "
            + "then return 0 end; redis.call('HSET',KEYS[1],'status',ARGV[2],'lease_owner','','lease_until','0','error',ARGV[3]); return 1";
        if (((Number) eval(script, keys(key("command", commandId)), args(workerId, status, text(error)))).longValue() != 1)
            throw new IllegalStateException("command is not claimed by worker: " + commandId);
    }

    private boolean sameCommand(AgentRunCommand left, AgentRunCommand right) {
        return left != null && left.getRunId().equals(right.getRunId())
            && left.getCommand().getType() == right.getCommand().getType()
            && Objects.equals(left.getCommand().getCorrelationId(), right.getCommand().getCorrelationId())
            && Objects.equals(left.getCommand().getContent(), right.getCommand().getContent())
            && left.getCommand().getMetadata().equals(right.getCommand().getMetadata());
    }

    private static long number(String value) { return value == null || value.isEmpty() ? 0 : Long.parseLong(value); }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
}
