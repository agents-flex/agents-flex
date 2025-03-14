package com.agentsflex.llm.deepseek;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.functions.annotation.FunctionDef;
import com.agentsflex.core.llm.functions.annotation.FunctionParam;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.util.StringUtil;

import java.util.Scanner;

public class DeepseekTest {


    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(@FunctionParam(name = "city", description = "城市名称") String name) {
        //在这里，我们应该通过第三方接口调用 api 信息
        return name + "的天气是阴转多云。 ";
    }

    @FunctionDef(name = "get_holiday_balance", description = "获取假期余额")
    public static String getHolidayBalance() {
        //在这里，我们应该通过第三方接口调用 api 信息
        String username = "michael";
        return username + "你的年假还剩余3天，有效期至26年1月。调休假剩余1天，长期有效。 ";
    }

    public static Llm getLLM() {
        DeepseekConfig deepseekConfig = new DeepseekConfig();
        deepseekConfig.setEndpoint("https://api.siliconflow.cn/v1");
        deepseekConfig.setApiKey("*********************");
        deepseekConfig.setModel("Pro/deepseek-ai/DeepSeek-V3");
        deepseekConfig.setDebug(true);
        return new DeepseekLlm(deepseekConfig);
    }

    public static void chatHr() {
        Llm llm = getLLM();
        HistoriesPrompt prompt = new HistoriesPrompt();
        // 加入system
        prompt.addMessage(new SystemMessage("你是一个人事助手小智，专注于为用户提供高效、精准的信息查询和问题解答服务。"));
        System.out.println("我是小智，你的人事小助手！请尽情吩咐小智！");
        Scanner scanner = new Scanner(System.in);
        String userInput = scanner.nextLine();
        while (userInput != null) {
            // 第二步：创建 HumanMessage，并添加方法调用
            HumanMessage humanMessage = new HumanMessage(userInput);
            humanMessage.addFunctions(DeepseekTest.class);
            // 第三步：将 HumanMessage 添加到 HistoriesPrompt 中
            prompt.addMessage(humanMessage);
            // 第四步：调用 chatStream 方法，进行对话
            llm.chatStream(prompt, new StreamResponseListener() {
                @Override
                public void onMessage(ChatContext context, AiMessageResponse response) {
                    if (StringUtil.hasText(response.getMessage().getContent())) {
                        System.out.print(response.getMessage().getContent());
                    }
                    if (response.getMessage().getStatus() == MessageStatus.END) {
                        System.out.println(response);
                        System.out.println("------");
                    }
                }

                @Override
                public void onStop(ChatContext context) {
                    System.out.println("stop!!!------");
                }
            });
            userInput = scanner.nextLine();
        }

    }


    public static void functionCall() {
        Llm llm = getLLM();
        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", DeepseekTest.class);
        AiMessageResponse response = llm.chat(prompt);
        System.out.println(response.callFunctions());
    }

    public static void main(String[] args) {
//        functionCall();
        chatHr();
    }
}
