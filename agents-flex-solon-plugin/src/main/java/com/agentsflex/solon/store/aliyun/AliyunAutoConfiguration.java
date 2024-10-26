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
package com.agentsflex.solon.store.aliyun;

import com.agentsflex.store.aliyun.AliyunVectorStore;
import com.agentsflex.store.aliyun.AliyunVectorStoreConfig;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

/**
 * 阿里云向量存储 自动配置
 */
@Configuration
@Condition(onClass = AliyunVectorStore.class)
public class AliyunAutoConfiguration {

    @Bean(typed = true)
    @Condition(onMissingBean = AliyunVectorStore.class)
    public AliyunVectorStore aliyunVectorStore(@Inject("${agents-flex.store.aliyun}") AliyunVectorStoreConfig config) {
        return new AliyunVectorStore(config);
    }

}
