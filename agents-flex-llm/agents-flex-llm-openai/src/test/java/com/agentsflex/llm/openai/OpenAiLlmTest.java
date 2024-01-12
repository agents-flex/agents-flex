package com.agentsflex.llm.openai;

import com.agentsflex.llm.Llm;
import com.agentsflex.prompt.SimplePrompt;

public class OpenAiLlmTest {

    public static void main(String[] args) throws InterruptedException {


        OpenAiConfig config = new OpenAiConfig();
        config.setApiKey("sk-rts5NF6n*******");

        Llm llm = new OpenAiLlm(config);

//        HistoriesPrompt prompt = new HistoriesPrompt();
//
//        System.out.println("您想问什么？");
//        Scanner scanner = new Scanner(System.in);
//        String userInput = scanner.nextLine();
//
//        while (userInput != null){
//
//            prompt.addMessage(new HumanMessage(userInput));
//
//            llm.chat(prompt, (instance, message) -> {
//                System.out.println(">>>> " + message.getContent());
//            });
//
//            userInput = scanner.nextLine();
//        }


        llm.chat(new SimplePrompt("请写一个小兔子战胜大灰狼的故事"), (instance, message) -> {
            System.out.println("--->" + message.getContent());
        });

        Thread.sleep(10000);

    }
}
