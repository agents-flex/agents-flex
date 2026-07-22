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

import com.agentsflex.skill.Skill;

import java.util.Collections;

/** Runtime 实现共享的 Skill bootstrap 执行与结果校验。 */
public final class SkillRuntimeBootstrap {

    private SkillRuntimeBootstrap() {
    }

    public static void run(SkillRuntime runtime, Skill runtimeSkill, SkillRuntimeConfig config) {
        if (config == null || config.getBootstrapCommands().isEmpty()) {
            return;
        }
        for (SkillBootstrapCommand command : config.getBootstrapCommands()) {
            SkillExecutionResult result = runtime.execute(new SkillExecutionRequest(
                command.getCommand(), runtimeSkill.getBasePath(), command.getTimeoutMillis(),
                Collections.<String, String>emptyMap()));
            if (result.isTimedOut()) {
                throw new SkillRuntimeException("Bootstrap command timed out for skill: " + runtimeSkill.name());
            }
            if (result.getExitCode() != 0) {
                throw new SkillRuntimeException("Bootstrap command failed for skill '" + runtimeSkill.name()
                    + "' with exit code " + result.getExitCode());
            }
        }
    }
}
