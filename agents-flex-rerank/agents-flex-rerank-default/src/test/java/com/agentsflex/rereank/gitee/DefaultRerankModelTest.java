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
package com.agentsflex.rereank.gitee;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.rerank.RerankException;
import com.agentsflex.rerank.DefaultRerankModel;
import com.agentsflex.rerank.DefaultRerankModelConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DefaultRerankModelTest {

    @Test(expected = RerankException.class)
    public void testRerank() {

        DefaultRerankModelConfig config = new DefaultRerankModelConfig();
        config.setEndpoint("https://ai.gitee.com");
        config.setRequestPath("/v1/rerank");
        config.setModel("Qwen3-Reranker-8B");
        config.setApiKey("*****");

        DefaultRerankModel model = new DefaultRerankModel(config);
        List<Document> documents = new ArrayList<>();
        documents.add(Document.of("Paris is the capital of France."));
        documents.add(Document.of("London is the capital of England."));
        documents.add(Document.of("Tokyo is the capital of Japan."));
        documents.add(Document.of("Beijing is the capital of China."));
        documents.add(Document.of("Washington, D.C. is the capital of the United States."));
        documents.add(Document.of("Moscow is the capital of Russia."));

        List<Document> rerank = model.rerank("What is the capital of France?", documents);
        System.out.println(rerank);

    }
}
