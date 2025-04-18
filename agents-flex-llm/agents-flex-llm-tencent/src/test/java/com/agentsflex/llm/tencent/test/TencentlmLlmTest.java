package com.agentsflex.llm.tencent.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.llm.tencent.TencentLlmConfig;
import com.agentsflex.llm.tencent.TencentlmLlm;
import org.junit.Test;

import java.util.Arrays;

public class TencentlmLlmTest {


    public static void main(String[] args) throws Exception {
        TencentLlmConfig config = new TencentLlmConfig();
        config.setApiSecret("******************");
        config.setApiKey("******************");
        Llm llm = new TencentlmLlm(config);
        for (int i =0;i<5;i++) {
            int finalI = i;
            new Thread(() -> {
                String response = llm.chat("你好");
                System.out.println(response + finalI);
            }).start();
        }

//        TencentLlmConfig config = new TencentLlmConfig();
//        config.setApiSecret("**************");
//        config.setApiKey("**************");
//        Llm llm = new TencentlmLlm(config);
//        List<Message> list = new ArrayList<>();
//        Message message = new HumanMessage("你好");
//        list.add(message);
//        AiMessage message2 = new AiMessage();
//        message2.setFullContent("你好！很高兴与你交流。请问有什么我可以帮助你的吗？无论是关于生活、工作、学习还是其他方面的问题，我都会尽力为你提供帮助。！");
//        list.add(message2);
//        message = new HumanMessage("好吧");
//        list.add(message);
//        HistoriesPrompt prompt = new HistoriesPrompt();
//        prompt.addMessages(list);
//        llm.chatStream(prompt, new StreamResponseListener() {
//            @Override
//            public void onMessage(ChatContext context, AiMessageResponse response) {
//                LogUtil.println("onMessage=====" + response.getMessage().getContent());
//            }
//
//            @Override
//            public void onStop(ChatContext context) {
//                LogUtil.println("停止");
//            }
//
//            @Override
//            public void onFailure(ChatContext context, Throwable throwable) {
//                LogUtil.println("出错" + throwable.getMessage());
//            }
//        });
    }


    @Test
    public void testEmbedding() {
        TencentLlmConfig config = new TencentLlmConfig();
        config.setApiSecret("******");
        config.setApiKey("***********");
        Llm llm = new TencentlmLlm(config);
        Document document = new Document();
        document.setContent("你好");
        VectorData embeddings = llm.embed(document, EmbeddingOptions.DEFAULT);
        System.out.println(Arrays.toString(embeddings.getVector()));
    }


    @Test
    public void testFunctionCalling() {
        TencentLlmConfig config = new TencentLlmConfig();
        config.setApiSecret("******");
        config.setApiKey("***********");

        Llm llm = new TencentlmLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
    }

}
