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
package com.agentsflex.store.redis.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.llm.spark.SparkLlm;
import com.agentsflex.llm.spark.SparkLlmConfig;
import com.agentsflex.store.redis.RedisVectorStore;
import com.agentsflex.store.redis.RedisVectorStoreConfig;

import java.util.List;

public class Test {
    public static void main(String[] args) {

        SparkLlmConfig sparkLlmConfig = new SparkLlmConfig();
//        sparkLlmConfig.setAppId("****");
//        sparkLlmConfig.setApiKey("****");
//        sparkLlmConfig.setApiSecret("****");


        Llm llm = new SparkLlm(sparkLlmConfig);

        RedisVectorStoreConfig config = new RedisVectorStoreConfig();
        config.setUri("redis://localhost:6379");
        config.setDefaultCollectionName("test");

        RedisVectorStore store = new RedisVectorStore(config);
        store.setEmbeddingModel(llm);

        SearchWrapper sw = new SearchWrapper();
        sw.setText("nihao");

        List<Document> search = store.search(sw);
        System.out.println(search);
    }
}
