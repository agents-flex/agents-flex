package com.agentsflex.llm.tencent.test;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.llm.tencent.TencentChatConfig;
import com.agentsflex.llm.tencent.TencentChatModel;
import org.junit.Test;

public class TencentChatModelTest {


    public static void main(String[] args) throws Exception {
        TencentChatConfig config = new TencentChatConfig();
        config.setApiSecret("******************");
        config.setApiKey("******************");
        ChatModel chatModel = new TencentChatModel(config);
        for (int i =0;i<5;i++) {
            int finalI = i;
            new Thread(() -> {
                String response = chatModel.chat("你好");
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
//                System.out.println("onMessage=====" + response.getMessage().getContent());
//            }
//
//            @Override
//            public void onStop(ChatContext context) {
//                System.out.println("停止");
//            }
//
//            @Override
//            public void onFailure(ChatContext context, Throwable throwable) {
//                System.out.println("出错" + throwable.getMessage());
//            }
//        });
    }




    @Test
    public void testFunctionCalling() {
        TencentChatConfig config = new TencentChatConfig();
        config.setApiSecret("******");
        config.setApiKey("***********");

        ChatModel chatModel = new TencentChatModel(config);

        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        System.out.println(response.callFunctions());
    }

}
