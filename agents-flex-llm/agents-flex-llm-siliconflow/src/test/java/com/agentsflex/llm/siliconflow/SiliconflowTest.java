package com.agentsflex.llm.siliconflow;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.store.VectorData;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class SiliconflowTest {

    @Test
    public void testChat() {
        SiliconflowConfig config = new SiliconflowConfig();
        config.setApiKey("sk-y*******************************************lkry");

        Llm llm = new SiliconflowLlm(config);
        String response = llm.chat("你叫什么名字");
        System.out.println(response);
    }

    @Test
    public void testChatStream() {
        SiliconflowConfig config = new SiliconflowConfig();
        config.setApiKey("sk-y*******************************************lkry");

        Llm llm = new SiliconflowLlm(config);
        llm.chatStream("你叫什么名字", (context, response) -> System.out.println(response.getMessage().getContent()));
    }

    @Test
    public void testEmbedding() {
        SiliconflowConfig config = new SiliconflowConfig();
        config.setApiKey("sk-y*******************************************lkry");
        Llm llm = new SiliconflowLlm(config);
        Document document = new Document();
        document.setContent("你好");
        VectorData embeddings = llm.embed(document, EmbeddingOptions.DEFAULT);
        System.out.println(Arrays.toString(embeddings.getVector()));
    }

    @Test
    public void testFunctionCalling() {
        SiliconflowConfig config = new SiliconflowConfig();
        config.setApiKey("sk-y*******************************************lkry");

        config.setModel("deepseek-ai/DeepSeek-V3");

        Llm llm = new SiliconflowLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        if (response.isError()) {
            Assert.fail(response.getErrorMessage());
        }
        System.out.println(response.callFunctions());
    }
}
