package com.agentsflex.core.agent.command;

import com.agentsflex.core.agent.AgentResumeCommand;

import java.io.Serializable;

/** 可持久化、可租约领取的 AgentRun 恢复命令。 */
public final class AgentRunCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String commandId;
    private final String runId;
    private final AgentResumeCommand command;
    private final long createdAt;
    private final AgentRunCommandStatus status;
    private final String leaseOwner;
    private final long leaseUntil;
    private final int attempts;
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

    public static AgentRunCommand pending(String commandId, String runId,
                                          AgentResumeCommand command) {
        return new AgentRunCommand(commandId, runId, command, System.currentTimeMillis(),
            AgentRunCommandStatus.PENDING, null, 0, 0, null);
    }

    public AgentRunCommand claimed(String workerId, long leaseUntil) {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.CLAIMED, workerId, leaseUntil, attempts + 1, null);
    }

    public AgentRunCommand completed() {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.COMPLETED, null, 0, attempts, null);
    }

    public AgentRunCommand failed(String error) {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.FAILED, null, 0, attempts, error);
    }

    public AgentRunCommand released(String error) {
        return new AgentRunCommand(commandId, runId, command, createdAt,
            AgentRunCommandStatus.PENDING, null, 0, attempts, error);
    }

    public String getCommandId() { return commandId; }
    public String getRunId() { return runId; }
    public AgentResumeCommand getCommand() { return command; }
    public long getCreatedAt() { return createdAt; }
    public AgentRunCommandStatus getStatus() { return status; }
    public String getLeaseOwner() { return leaseOwner; }
    public long getLeaseUntil() { return leaseUntil; }
    public int getAttempts() { return attempts; }
    public String getErrorMessage() { return errorMessage; }
}
