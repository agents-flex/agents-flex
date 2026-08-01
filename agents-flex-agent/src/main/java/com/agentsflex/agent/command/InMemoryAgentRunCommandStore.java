package com.agentsflex.agent.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内命令收件箱，支持幂等提交和租约领取。
 *
 * <p>全部公开操作通过对象锁保证原子性。该实现不支持多进程共享或重启恢复，只适合测试和
 * 单实例应用；分布式部署应使用 JDBC、Redis 等共享 Store。</p>
 */
public final class InMemoryAgentRunCommandStore implements AgentRunCommandStore {
    /** 按 commandId 保持提交顺序的命令映射。 */
    private final Map<String, AgentRunCommand> commands = new LinkedHashMap<>();

    /** 按 commandId 幂等保存；相同 ID 对应不同内容时拒绝覆盖。 */
    @Override
    public synchronized AgentRunCommand submit(AgentRunCommand command) {
        AgentRunCommand existing = commands.get(command.getCommandId());
        if (existing != null) {
            if (!sameCommand(existing, command)) {
                throw new IllegalArgumentException(
                    "commandId is already used by a different command: "
                        + command.getCommandId());
            }
            return existing;
        }
        commands.put(command.getCommandId(), command);
        return command;
    }

    /** 查询当前命令对象；不存在时返回 {@code null}。 */
    @Override
    public synchronized AgentRunCommand load(String commandId) {
        return commands.get(commandId);
    }

    /** 按提交顺序原子领取待处理或租约已过期的命令。 */
    @Override
    public synchronized List<AgentRunCommand> claim(String workerId, long now,
                                                    long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid command claim request");
        }
        List<AgentRunCommand> result = new ArrayList<>();
        for (Map.Entry<String, AgentRunCommand> entry : commands.entrySet()) {
            AgentRunCommand value = entry.getValue();
            boolean claimable = value.getStatus() == AgentRunCommandStatus.PENDING
                || (value.getStatus() == AgentRunCommandStatus.CLAIMED
                    && value.getLeaseUntil() <= now);
            if (!claimable) continue;
            AgentRunCommand claimed = value.claimed(workerId, now + leaseMillis);
            entry.setValue(claimed);
            result.add(claimed);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** 确认当前 Worker 已成功应用命令。 */
    @Override
    public synchronized void acknowledge(String commandId, String workerId) {
        updateClaimed(commandId, workerId, true, null);
    }

    /** 释放当前 Worker 的命令租约并保留错误，允许再次领取。 */
    @Override
    public synchronized void release(String commandId, String workerId, String errorMessage) {
        updateClaimed(commandId, workerId, false, errorMessage);
    }

    /** 把当前 Worker 领取的命令标记为不可重试的失败状态。 */
    @Override
    public synchronized void fail(String commandId, String workerId, String errorMessage) {
        AgentRunCommand current = requireClaim(commandId, workerId);
        commands.put(commandId, current.failed(errorMessage));
    }

    private void updateClaimed(String commandId, String workerId, boolean completed,
                               String errorMessage) {
        AgentRunCommand current = requireClaim(commandId, workerId);
        commands.put(commandId, completed ? current.completed() : current.released(errorMessage));
    }

    private AgentRunCommand requireClaim(String commandId, String workerId) {
        AgentRunCommand current = commands.get(commandId);
        if (current == null || current.getStatus() != AgentRunCommandStatus.CLAIMED
            || !workerId.equals(current.getLeaseOwner())) {
            throw new IllegalStateException("command is not claimed by worker: " + commandId);
        }
        return current;
    }

    /** 防止同一个幂等键被误用于另一个 Run 或另一种恢复动作。 */
    private boolean sameCommand(AgentRunCommand left, AgentRunCommand right) {
        if (!left.getRunId().equals(right.getRunId())) return false;
        if (left.getCommand().getType() != right.getCommand().getType()) return false;
        if (!java.util.Objects.equals(left.getCommand().getCorrelationId(),
            right.getCommand().getCorrelationId())) return false;
        if (!java.util.Objects.equals(left.getCommand().getContent(),
            right.getCommand().getContent())) return false;
        return left.getCommand().getMetadata().equals(right.getCommand().getMetadata());
    }
}
