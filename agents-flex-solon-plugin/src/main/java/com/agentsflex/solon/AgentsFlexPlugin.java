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
package com.agentsflex.solon;

import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 * agents flex solon plugin
 *
 * @author songyinyin
 * @since 2024/10/25 下午10:18
 */
public class AgentsFlexPlugin implements Plugin {

    @Override
    public void start(AppContext context) throws Throwable {
        context.beanScan(AgentsFlexPlugin.class);
    }
}
