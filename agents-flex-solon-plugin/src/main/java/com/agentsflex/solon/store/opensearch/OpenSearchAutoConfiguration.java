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
package com.agentsflex.solon.store.opensearch;

import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.store.opensearch.OpenSearchVectorStore;
import com.agentsflex.store.opensearch.OpenSearchVectorStoreConfig;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * opensearch向量存储 自动配置
 *
 * @author songyinyin
 * @since 2024/8/13 上午11:26
 */
@Configuration
@Condition(onClass = OpenSearchVectorStore.class)
public class OpenSearchAutoConfiguration {

    @Bean
    @Condition(onMissingBean = OpenSearchVectorStore.class)
    public DocumentStore openSearchVectorStore(@Inject("${agents-flex.store.opensearch}") OpenSearchVectorStoreConfig config,
                                               @Inject(required = false) OpenSearchClient client) {
        if (client != null) {
            return new OpenSearchVectorStore(config, client);
        }
        return new OpenSearchVectorStore(config);
    }
}
