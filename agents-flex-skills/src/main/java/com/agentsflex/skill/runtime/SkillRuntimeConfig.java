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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一个 Skill 在 Runtime 中使用的环境变量和初始化命令。
 *
 * <p>该配置属于 Agents-Flex 执行层，不会写入或修改标准 {@code SKILL.md}。环境变量
 * 会在当前 Runtime 生命周期内保存，并注入 bootstrap 及后续的每次命令执行。</p>
 */
public class SkillRuntimeConfig {

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, String> environment;
    private final List<SkillBootstrapCommand> bootstrapCommands;

    public SkillRuntimeConfig(Map<String, String> environment,
                              List<SkillBootstrapCommand> bootstrapCommands) {
        this.environment = immutableEnvironment(environment);
        this.bootstrapCommands = bootstrapCommands == null
            ? Collections.<SkillBootstrapCommand>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(bootstrapCommands));
        for (SkillBootstrapCommand command : this.bootstrapCommands) {
            if (command == null) {
                throw new IllegalArgumentException("bootstrapCommands must not contain null");
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public List<SkillBootstrapCommand> getBootstrapCommands() {
        return bootstrapCommands;
    }

    private static Map<String, String> immutableEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            if (name == null || !ENVIRONMENT_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid environment variable name: " + name);
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Environment variable value must not be null: " + name);
            }
            copy.put(name, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    public static class Builder {

        private final Map<String, String> environment = new LinkedHashMap<>();
        private final List<SkillBootstrapCommand> bootstrapCommands = new ArrayList<>();

        public Builder environment(Map<String, String> environment) {
            if (environment != null) {
                this.environment.putAll(environment);
            }
            return this;
        }

        public Builder environment(String name, String value) {
            this.environment.put(name, value);
            return this;
        }

        public Builder bootstrapCommand(String command) {
            return bootstrapCommand(new SkillBootstrapCommand(command));
        }

        public Builder bootstrapCommand(String command, long timeoutMillis) {
            return bootstrapCommand(new SkillBootstrapCommand(command, timeoutMillis));
        }

        public Builder bootstrapCommand(SkillBootstrapCommand command) {
            if (command == null) {
                throw new IllegalArgumentException("bootstrapCommand must not be null");
            }
            this.bootstrapCommands.add(command);
            return this;
        }

        public SkillRuntimeConfig build() {
            return new SkillRuntimeConfig(environment, bootstrapCommands);
        }
    }
}
