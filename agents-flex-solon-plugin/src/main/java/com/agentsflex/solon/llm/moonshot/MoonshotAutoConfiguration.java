/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.solon.llm.moonshot;

import com.agentsflex.llm.moonshot.MoonshotLlm;
import com.agentsflex.llm.moonshot.MoonshotLlmConfig;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

/**
 * 月之暗面 自动配置
 */
@Configuration
@Condition(onClass = MoonshotLlm.class)
public class MoonshotAutoConfiguration {

    @Bean(typed = true)
    @Condition(onMissingBean = MoonshotLlm.class)
    public MoonshotLlm moonshotLlm(@Inject("agents-flex.llm.moonshot") MoonshotLlmConfig config) {
        return new MoonshotLlm(config);
    }

}
