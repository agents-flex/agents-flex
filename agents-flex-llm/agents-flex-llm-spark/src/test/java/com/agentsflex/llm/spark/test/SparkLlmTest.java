package com.agentsflex.llm.spark.test;

import com.agentsflex.llm.Llm;
import com.agentsflex.llm.spark.SparkLlm;
import com.agentsflex.llm.spark.SparkLlmConfig;
import com.agentsflex.message.HumanMessage;
import com.agentsflex.prompt.HistoriesPrompt;
import org.junit.Test;

import java.util.Scanner;

public class SparkLlmTest {

    @Test
    public void testSimple() {
        SparkLlmConfig config = new SparkLlmConfig();
        config.setAppId("****");
        config.setApiKey("****");
        config.setApiSecret("****");

        Llm llm = new SparkLlm(config);
        String result = llm.chat("你好");
        System.out.println(result);
    }


    public static void main(String[] args) {
        SparkLlmConfig config = new SparkLlmConfig();
        config.setAppId("****");
        config.setApiKey("****");
        config.setApiSecret("****");

        Llm llm = new SparkLlm(config);

        HistoriesPrompt prompt = new HistoriesPrompt();

        System.out.println("您想问什么？");
        Scanner scanner = new Scanner(System.in);
        String userInput = scanner.nextLine();

        while (userInput != null) {

            prompt.addMessage(new HumanMessage(userInput));

            llm.chatAsync(prompt, (context, response) -> {
                System.out.println(">>>> " + response.getMessage().getContent());
            });

            userInput = scanner.nextLine();
        }
    }
}
