package com.agentsflex.agent.command;

import java.util.List;

/** 持久化恢复命令的收件箱接口。 */
public interface AgentRunCommandStore {
    /** 按 commandId 幂等保存命令，重复提交返回已有记录。 */
    AgentRunCommand submit(AgentRunCommand command);
    /** 查询命令当前状态，不存在时返回 null。 */
    AgentRunCommand load(String commandId);
    /** 原子领取待处理或租约已过期的命令，并写入新的命令租约。 */
    List<AgentRunCommand> claim(String workerId, long now, long leaseMillis, int limit);
    /** 确认命令处理完成，只有当前租约持有者可以调用。 */
    void acknowledge(String commandId, String workerId);
    /** 释放命令供后续重试，并保存最近一次错误。 */
    void release(String commandId, String workerId, String errorMessage);
    /** 将多次无法处理的命令标记为终止失败。 */
    void fail(String commandId, String workerId, String errorMessage);
}
