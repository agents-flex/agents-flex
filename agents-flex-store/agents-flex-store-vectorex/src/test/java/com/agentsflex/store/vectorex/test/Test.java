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
package com.agentsflex.store.vectorex.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.llm.spark.SparkChatModel;
import com.agentsflex.llm.spark.SparkChatConfig;
import com.agentsflex.store.vectorex.VectoRexStore;
import com.agentsflex.store.vectorex.VectoRexStoreConfig;

import java.util.List;

public class Test {
    public static void main(String[] args) {

        SparkChatConfig sparkLlmConfig = new SparkChatConfig();
//        sparkLlmConfig.setAppId("****");
//        sparkLlmConfig.setApiKey("****");
//        sparkLlmConfig.setApiSecret("****");

        sparkLlmConfig.setAppId("9e868ce4");
        sparkLlmConfig.setApiKey("a15d512f688abe35982932ce2f294121");
        sparkLlmConfig.setApiSecret("N2Y5OTgwMDM4ODJkNWUxZjgwZWE1MzFj");


        ChatModel chatModel = new SparkChatModel(sparkLlmConfig);

        VectoRexStoreConfig config = new VectoRexStoreConfig();
        config.setUri("https://localhost:8230");
        config.setDefaultCollectionName("test05");
        config.setUsername("");
        config.setPassword("");
        VectoRexStore store = new VectoRexStore(config);
//        store.setEmbeddingModel(chatModel);

        Document document = new Document();
        document.setContent("你好");
        document.setId(1);
        store.store(document);

        SearchWrapper sw = new SearchWrapper();
        sw.setText("你好");

        List<Document> search = store.search(sw);
        System.out.println(search);


        StoreResult result = store.delete("1");
        LogUtil.println("-------delete-----" + result);
        search = store.search(sw);
        System.out.println(search);
    }
}
