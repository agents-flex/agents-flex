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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一批 Skills 及其可选 Runtime 配置。
 */
public class SkillPreparationRequest {

    private final List<Skill> skills;
    private final Map<String, SkillRuntimeConfig> runtimeConfigs;

    public SkillPreparationRequest(List<Skill> skills,
                                   Map<String, SkillRuntimeConfig> runtimeConfigs) {
        if (skills == null) {
            throw new IllegalArgumentException("skills must not be null");
        }
        this.skills = Collections.unmodifiableList(new ArrayList<>(skills));
        this.runtimeConfigs = runtimeConfigs == null
            ? Collections.<String, SkillRuntimeConfig>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(runtimeConfigs));
        validateConfigs();
    }

    public List<Skill> getSkills() {
        return skills;
    }

    public Map<String, SkillRuntimeConfig> getRuntimeConfigs() {
        return runtimeConfigs;
    }

    public SkillRuntimeConfig getRuntimeConfig(Skill skill) {
        return runtimeConfigs.get(skill.name());
    }

    private void validateConfigs() {
        Set<String> skillNames = new LinkedHashSet<>();
        for (Skill skill : skills) {
            if (skill == null) {
                throw new IllegalArgumentException("skills must not contain null");
            }
            skillNames.add(skill.name());
        }
        for (Map.Entry<String, SkillRuntimeConfig> entry : runtimeConfigs.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("runtime config skill name must not be blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("runtime config must not be null: " + name);
            }
            if (!skillNames.contains(name)) {
                throw new IllegalArgumentException("Runtime config references an unknown skill: " + name);
            }
        }
    }
}
