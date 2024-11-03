package com.agentsflex.llm.spark.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.exception.LlmException;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.llm.spark.SparkLlm;
import com.agentsflex.llm.spark.SparkLlmConfig;
import org.junit.Test;

import java.util.Scanner;

public class SparkLlmTest {

    private static SparkLlm getSparkLLM() {
        SparkLlmConfig config = new SparkLlmConfig();
        config.setAppId("****");
        config.setApiKey("****");
        config.setApiSecret("****");


        config.setDebug(true);
        return new SparkLlm(config);
    }

    @Test(expected = LlmException.class)
    public void testSimple() {
        Llm llm = getSparkLLM();
        String result = llm.chat("你好，请问你是谁？");
        System.out.println(result);
    }

    @Test
    public void testEmbedding() {
        Llm llm = getSparkLLM();
        VectorData vectorData = llm.embed(Document.of("你好，请问你是谁？"));
        System.out.println(vectorData);
    }


    @Test
    public void testFunctionCalling() throws InterruptedException {
        Llm llm = getSparkLLM();
        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
    }


    public static void main(String[] args) {
        Llm llm = getSparkLLM();

        HistoriesPrompt prompt = new HistoriesPrompt();

        System.out.println("您想问什么？");
        Scanner scanner = new Scanner(System.in);
        String userInput = scanner.nextLine();

        while (userInput != null) {

            prompt.addMessage(new HumanMessage(userInput));

            llm.chatStream(prompt, (context, response) -> {
                System.out.println(">>>> " + response.getMessage().getContent());
            });

            userInput = scanner.nextLine();
        }
    }
}
