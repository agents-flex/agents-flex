package com.agentsflex.llm.spark.test;

import com.agentsflex.llm.Llm;
import com.agentsflex.llm.spark.SparkLlm;
import com.agentsflex.llm.spark.SparkLlmConfig;
import com.agentsflex.message.HumanMessage;
import com.agentsflex.prompt.HistoriesPrompt;

import java.util.Scanner;

public class SparkLlmTest {

    public static void main(String[] args) throws InterruptedException {


        SparkLlmConfig config = new SparkLlmConfig();
        config.setAppId("****");
        config.setApiKey("****");
        config.setApiSecret("****");

        Llm llm = new SparkLlm(config);

        HistoriesPrompt prompt = new HistoriesPrompt();

        System.out.println("您想问什么？");
        Scanner scanner = new Scanner(System.in);
        String userInput = scanner.nextLine();

        while (userInput != null){

            prompt.addMessage(new HumanMessage(userInput));

            llm.chat(prompt, (instance, message) -> {
                System.out.println(">>>> " + message.getContent());
            });

            userInput = scanner.nextLine();
        }



    }
}
