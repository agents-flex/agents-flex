package com.agentsflex.agent.command;

import com.agentsflex.agent.AgentResumeCommand;

import java.io.Serializable;

/**
 * 可持久化、可租约领取的 AgentRun 恢复命令。
 *
 * <p>外部系统先按 commandId 幂等提交命令，Worker 再通过命令 Store 原子领取。对象采用不可变
 * 状态转换，领取、完成、失败和释放都会返回新对象，便于 Store 使用乐观更新或事务写入。</p>
 */
public final class AgentRunCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 调用方提供的幂等命令 ID。 */
    private final String commandId;
    /** 等待恢复的目标 AgentRun ID。 */
    private final String runId;
    /** 要应用到阻塞点的结构化恢复动作。 */
    private final AgentResumeCommand command;
    /** 命令首次提交时的毫秒时间戳。 */
    private final long createdAt;
    /** 命令当前的持久化处理状态。 */
    private final AgentRunCommandStatus status;
    /** 当前领取命令的 Worker ID；未领取时为 {@code null}。 */
    private final String leaseOwner;
    /** 命令租约到期毫秒时间戳；没有租约时为 0。 */
    private final long leaseUntil;
    /** 命令累计被领取的次数。 */
    private final int attempts;
    /** 最近一次释放或最终失败的错误说明。 */
    private final String errorMessage;

    public AgentRunCommand(String commandId, String runId, AgentResumeCommand command,
                           long createdAt, AgentRunCommandStatus status, String leaseOwner,
                           long leaseUntil, int attempts, String errorMessage) {
        if (commandId == null || runId == null || command == null || status == null) {
            throw new IllegalArgumentException("commandId, runId, command and status are required");
        }
        this.commandId = commandId;
        this.runId = runId;
        this.command = command;
        this.createdAt = createdAt;
        this.status = status;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.attempts = attempts;
        this.errorMessage = errorMessage;
    }

    /** 创建一条尚未被 Worker 领取的命令。 */
    public static AgentRunCommand pending(String commandId, String runId,
                                          AgentResumeCommand command) {
        return new AgentRunCommand(commandId, runId, command, System.currentTimeMillis(),
            AgentRunCommandStatus.PENDING, null, 0, 0, null);
    }

    /** 返回由指定 Worker 领取并增加尝试次数的新状态。 */
    public AgentRunCommand claimed(String workerId, long leaseUntil) {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.CLAIMED, workerId, leaseUntil, attempts + 1, null);
    }

    /** 返回已经成功应用到 Run 的终止状态。 */
    public AgentRunCommand completed() {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.COMPLETED, null, 0, attempts, null);
    }

    /** 返回不再重试的失败终止状态。 */
    public AgentRunCommand failed(String error) {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.FAILED, null, 0, attempts, error);
    }

    /** 返回释放租约、等待后续 Worker 再次领取的状态。 */
    public AgentRunCommand released(String error) {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.PENDING, null, 0, attempts, error);
    }

    /** @return 幂等命令 ID */
    public String getCommandId() { return commandId; }
    /** @return 目标 AgentRun ID */
    public String getRunId() { return runId; }
    /** @return 需要应用的恢复动作 */
    public AgentResumeCommand getCommand() { return command; }
    /** @return 首次提交时间 */
    public long getCreatedAt() { return createdAt; }
    /** @return 当前处理状态 */
    public AgentRunCommandStatus getStatus() { return status; }
    /** @return 当前租约持有者；没有租约时为 {@code null} */
    public String getLeaseOwner() { return leaseOwner; }
    /** @return 租约到期时间；没有租约时为 0 */
    public long getLeaseUntil() { return leaseUntil; }
    /** @return 累计领取次数 */
    public int getAttempts() { return attempts; }
    /** @return 最近一次错误说明；尚未出错时为 {@code null} */
    public String getErrorMessage() { return errorMessage; }
}
