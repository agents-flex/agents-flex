package com.agentsflex.llm.spark.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.llm.spark.SparkChatModel;
import com.agentsflex.llm.spark.SparkChatConfig;
import org.junit.Test;

import java.util.Scanner;

public class SparkChatModelTest {

    private static SparkChatModel getSparkLlm() {
        SparkChatConfig config = new SparkChatConfig();
        config.setAppId("****");
        config.setApiKey("****");
        config.setApiSecret("****");


        config.setDebug(true);
        return new SparkChatModel(config);
    }

    @Test(expected = ModelException.class)
    public void testSimple() {
        ChatModel chatModel = getSparkLlm();
        String result = chatModel.chat("你好，请问你是谁？");
        System.out.println(result);
    }

    @Test
    public void testEmbedding() {
        ChatModel chatModel = getSparkLlm();
        VectorData vectorData = chatModel.embed(Document.of("你好，请问你是谁？"));
        System.out.println(vectorData);
    }


    @Test
    public void testFunctionCalling() throws InterruptedException {
        ChatModel chatModel = getSparkLlm();
        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        System.out.println(response.callFunctions());
    }


    public static void main(String[] args) {
        ChatModel chatModel = getSparkLlm();

        HistoriesPrompt prompt = new HistoriesPrompt();

        LogUtil.println("您想问什么？");
        Scanner scanner = new Scanner(System.in);
        String userInput = scanner.nextLine();

        while (userInput != null) {

            prompt.addMessage(new HumanMessage(userInput));

            chatModel.chatStream(prompt, (context, response) -> {
                LogUtil.println(">>>> " + response.getMessage().getContent());
            });

            userInput = scanner.nextLine();
        }
    }
}
