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

import com.agentsflex.llm.openai.OpenAIChatModel;
import com.agentsflex.llm.openai.OpenAIChatConfig;
import com.agentsflex.store.vectorex.VectoRexStore;
import com.agentsflex.store.vectorex.VectoRexStoreConfig;

import java.util.List;

public class Test {
    public static void main(String[] args) {

        OpenAIChatConfig openAILlmConfig= new OpenAIChatConfig();
        openAILlmConfig.setApiKey("");
        openAILlmConfig.setEndpoint("");
        openAILlmConfig.setModel("");

        ChatModel chatModel = new OpenAIChatModel(openAILlmConfig);


        VectoRexStoreConfig config = new VectoRexStoreConfig();
        config.setDefaultCollectionName("test05");
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
        System.out.println("-------delete-----" + result);
        search = store.search(sw);
        System.out.println(search);
    }
}
