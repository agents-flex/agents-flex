/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.skill.runtime;

/**
 * Skill 上传到 Runtime 后执行的一条初始化命令。
 */
public class SkillBootstrapCommand {

    public static final long DEFAULT_TIMEOUT_MILLIS = 120000L;

    private final String command;
    private final long timeoutMillis;

    /**
     * 使用默认超时时间创建初始化命令。
     *
     * @param command Shell 命令，工作目录为 Runtime 内的 Skill 根目录
     */
    public SkillBootstrapCommand(String command) {
        this(command, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * @param command Shell 命令，工作目录为 Runtime 内的 Skill 根目录
     * @param timeoutMillis 最长执行时间，必须大于 0
     */
    public SkillBootstrapCommand(String command, long timeoutMillis) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("bootstrap command must not be empty");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("bootstrap timeoutMillis must be greater than zero");
        }
        this.command = command;
        this.timeoutMillis = timeoutMillis;
    }

    public String getCommand() {
        return command;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }
}
