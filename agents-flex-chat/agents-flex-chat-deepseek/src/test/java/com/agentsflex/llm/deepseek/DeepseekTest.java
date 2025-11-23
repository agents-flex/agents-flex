package com.agentsflex.llm.deepseek;

import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.core.util.StringUtil;

import java.util.Scanner;

public class DeepseekTest {


    @ToolDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(@ToolParam(name = "city", description = "城市名称") String name) {
        //在这里，我们应该通过第三方接口调用 api 信息
        return name + "的天气是阴转多云。 ";
    }

    @ToolDef(name = "get_holiday_balance", description = "获取假期余额")
    public static String getHolidayBalance() {
        //在这里，我们应该通过第三方接口调用 api 信息
        String username = "michael";
        return username + "你的年假还剩余3天，有效期至26年1月。调休假剩余1天，长期有效。 ";
    }

    public static ChatModel getLLM() {
        DeepseekConfig deepseekConfig = new DeepseekConfig();
        deepseekConfig.setEndpoint("https://api.siliconflow.cn/v1");
        deepseekConfig.setApiKey("*********************");
        deepseekConfig.setModel("Pro/deepseek-ai/DeepSeek-V3");
        deepseekConfig.setLogEnabled(true);
        return new DeepseekChatModel(deepseekConfig);
    }

    public static void chatHr() {
        ChatModel chatModel = getLLM();
        HistoriesPrompt prompt = new HistoriesPrompt();
        // 加入system
        prompt.addMessage(new SystemMessage("你是一个人事助手小智，专注于为用户提供高效、精准的信息查询和问题解答服务。"));
        System.out.println("我是小智，你的人事小助手！请尽情吩咐小智！");
        Scanner scanner = new Scanner(System.in);
        String userInput = scanner.nextLine();
        while (userInput != null) {
            // 第二步：创建 HumanMessage，并添加方法调用
            UserMessage userMessage = new UserMessage(userInput);
            userMessage.addToolsFromClass(DeepseekTest.class);
            // 第三步：将 HumanMessage 添加到 HistoriesPrompt 中
            prompt.addMessage(userMessage);
            // 第四步：调用 chatStream 方法，进行对话
            chatModel.chatStream(prompt, new StreamResponseListener() {
                @Override
                public void onMessage(StreamContext context, AiMessageResponse response) {
                    if (StringUtil.hasText(response.getMessage().getContent())) {
                        System.out.print(response.getMessage().getContent());
                    }
                    if (response.getMessage().isLastMessage()) {
                        System.out.println(response);
                        System.out.println("------");
                    }
                }

                @Override
                public void onStop(StreamContext context) {
                    System.out.println("stop!!!------");
                }
            });
            userInput = scanner.nextLine();
        }

    }


    public static void functionCall() {
        ChatModel chatModel = getLLM();
        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addToolsFromClass(DeepseekTest.class);
        AiMessageResponse response = chatModel.chat(prompt);
        System.out.println(response.executeToolCallsAndGetResults());
    }

    public static void main(String[] args) {
//        functionCall();
        chatHr();
    }
}
