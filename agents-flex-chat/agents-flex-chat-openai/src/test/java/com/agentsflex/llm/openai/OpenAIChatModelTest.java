package com.agentsflex.llm.openai;

import com.agentsflex.core.agents.react.ReActAgent;
import com.agentsflex.core.agents.react.ReActAgentListener;
import com.agentsflex.core.agents.react.ReActAgentState;
import com.agentsflex.core.agents.react.ReActStep;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.functions.Function;
import com.agentsflex.core.model.chat.functions.JavaNativeFunctionBuilder;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class OpenAIChatModelTest {

    @Test(expected = ModelException.class)
    public void testChat() {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey("sk-rts5NF6n*******");

        ChatModel chatModel = new OpenAIChatModel(config);
        String response = chatModel.chat("请问你叫什么名字");

        System.out.println(response);
    }

    @Test()
    public void testChatStream() {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey("sk-alQ9N********");
        config.setEndpoint("https://api.moonshot.cn");
        config.setModel("moonshot-v1-8k");
//        config.setDebug(true);

        ChatModel chatModel = new OpenAIChatModel(config);
        chatModel.chatStream("你叫什么名字", new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                System.out.println(response.getMessage().getContent());
            }
        });

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testChatOllama() {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
        config.setLogEnabled(true);

        ChatModel chatModel = new OpenAIChatModel(config);
        chatModel.chatStream("who are you", new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                System.out.println(response.getMessage().getContent());
            }

            @Override
            public void onStop(StreamContext context) {
                System.out.println("stop!!!!");
            }
        });

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    @Test()
    public void testChatWithImage() {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey("sk-5gqOcl*****");
        config.setModel("gpt-4-turbo");


        ChatModel chatModel = new OpenAIChatModel(config);
        SimplePrompt prompt = new SimplePrompt("What's in this image?");
        prompt.addImageUrl("https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg");


        AiMessageResponse response = chatModel.chat(prompt);
        System.out.println(response);
    }


    @Test()
    public void testFunctionCalling1() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey("sk-rts5NF6n*******");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.getFunctionResults());
        // 阴转多云
    }

    @Test()
    public void testFunctionCalling2() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey("sk-rts5NF6n*******");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        if (response.isFunctionCall()) {
            prompt.setToolMessages(response.getToolMessages());
            AiMessageResponse response1 = llm.chat(prompt);
            System.out.println(response1.getMessage().getContent());
        } else {
            System.out.println(response);
        }
    }

    @Test()
    public void testFunctionCalling3() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setLogEnabled(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setRequestPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d57a");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        if (response.isFunctionCall()) {
            prompt.setToolMessages(response.getToolMessages());
            AiMessageResponse response1 = llm.chat(prompt);
            System.out.println(response1.getMessage().getContent());
        } else {
            System.out.println(response);
        }
    }

    @Test()
    public void testFunctionCalling4() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setLogEnabled(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setRequestPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d57aa75");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                System.out.println(" onMessage >>>>>" + response.isFunctionCall());
            }
        });

        TimeUnit.SECONDS.sleep(5);
    }

    @Test()
    public void testFunctionCalling44() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setLogEnabled(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setRequestPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d5");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                System.out.println(" onMessage >>>>>" + response.isFunctionCall());
            }
        });

        TimeUnit.SECONDS.sleep(5);
    }

    @Test()
    public void testFunctionCalling444() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setLogEnabled(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setRequestPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d57");

        OpenAIChatModel llm = new OpenAIChatModel(config);


        SimplePrompt prompt = new SimplePrompt("北京和上海的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {


//                if (response.isFunctionCall()) {
//                    List<FunctionCaller> functionCallers = response.getFunctionCallers();
//                    boolean isEmitter = false;
//                    boolean isComplete = false;
//                    for (FunctionCaller functionCaller : functionCallers) {
//                        Object result = functionCaller.call();
//                        if (result != null){
//                            isEmitter = true;
//                            // sentEmiiter(....)
//                        }
////                        if (result == null){
////                            continue;
////                        }
//                        System.out.println(result);
//                    }
//
//
//                    if (!isEmitter){
//                        // ......
//                    }
//                }

                System.out.println("onMessage >>>>>" + response);
                if (response.isFunctionCall()) {
                    System.out.println(":::::::: start....");
                    prompt.setToolMessages(response.getToolMessages());
                    llm.chatStream(prompt, new StreamResponseListener() {
                        @Override
                        public void onMessage(StreamContext context, AiMessageResponse response) {
                            String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                            System.out.println(":::" + msg);
                        }
                    });
                } else {
                    String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                    System.out.println(">>>" + msg);
                }
            }
        });

        TimeUnit.SECONDS.sleep(25);

    }


    @Test()
    public void testFunctionCalling5() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setLogEnabled(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen3-32B");
        config.setApiKey("PXW1GXEKF8ZNQ1NP0UCYN5SUFASW4KI3YLQL7D12");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("北京和上海的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
//                System.out.println("onMessage >>>>>" + response);
            }
        });

        TimeUnit.SECONDS.sleep(1115);
    }


    @Test()
    public void testFunctionCalling55() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setLogEnabled(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen3-32B");
        config.setApiKey("PXW1");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("/no_think 北京和上海的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
//        FunctionPrompt prompt = new FunctionPrompt("上海的天气怎么样", WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
//                System.out.println("onMessage >>>>>" + response);
                if (response.isFunctionCall()) {
                    System.out.println(":::::::: start....");
                    prompt.setToolMessages(response.getToolMessages());
                    llm.chatStream(prompt, new StreamResponseListener() {
                        @Override
                        public void onMessage(StreamContext context, AiMessageResponse response) {
                            String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                            System.out.println(":::" + msg);
                        }
                    });
                } else {
                    String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                    System.out.println(">>>" + msg);
                }
            }
        });

        TimeUnit.SECONDS.sleep(25);
    }

    @Test()
    public void testFunctionCalling6() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setLogEnabled(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen3-32B");
        config.setApiKey("PXW1");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("/no_think 北京和上海的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        prompt.setToolMessages(response.getToolMessages());

        System.out.println(llm.chat(prompt));

    }


    @Test()
    public void testReAct1() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
//        config.setDebug(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen3-32B");
        config.setApiKey("****");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        List<Function> functions = JavaNativeFunctionBuilder.fromClass(WeatherFunctions.class);
//        ReActAgent reActAgent = new ReActAgent(llm, functions, "北京和上海的天气怎么样？");
        ReActAgent reActAgent = new ReActAgent(llm, functions, "介绍一下北京");
        reActAgent.addListener(new ReActAgentListener() {

            @Override
            public void onActionStart(ReActStep step) {
                System.out.println(">>>>>>" + step.getThought());
                System.out.println("正在调用工具 >>>>> " + step.getAction() + ":" + step.getActionInput());
            }

            @Override
            public void onActionEnd(ReActStep step, Object result) {
                System.out.println("工具调用结束 >>>>> " + step.getAction() + ":" + step.getActionInput() + ">>>>结果：" + result);
            }

            @Override
            public void onFinalAnswer(String finalAnswer) {
                System.out.println("onFinalAnswer >>>>>" + finalAnswer);
            }

            @Override
            public void onNonActionResponse(AiMessageResponse response) {
                System.out.println("onNonActionResponse >>>>>" + response.getMessage().getContent());
            }
        });

        reActAgent.execute();
    }


    @Test()
    public void testReAct2() throws InterruptedException {
        OpenAIChatConfig config = new OpenAIChatConfig();
//        config.setDebug(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen2-72B-Instruct");
        config.setApiKey("*****");

        OpenAIChatModel llm = new OpenAIChatModel(config);

        List<Function> functions = JavaNativeFunctionBuilder.fromClass(WeatherFunctions.class);
        ReActAgent reActAgent = new ReActAgent(llm, functions, "今天的天气怎么样？");
//        reActAgent.setStreamable(true);
        reActAgent.addListener(new ReActAgentListener() {

            @Override
            public void onChatResponseStream(StreamContext context, AiMessageResponse response) {
//                System.out.print(response.getMessage().getContent());
            }

            @Override
            public void onRequestUserInput(String question) {
                System.out.println("onRequestUserInput>>>" + question);

                ReActAgentState state = reActAgent.getState();
                state.addMessage(new UserMessage("我在北京市"));
                ReActAgent newAgent = new ReActAgent(llm, functions, state);
                newAgent.addListener(this);
                newAgent.execute();
            }

            @Override
            public void onActionStart(ReActStep step) {
                System.out.println(">>>>>>" + step.getThought());
                System.out.println("正在调用工具 >>>>> " + step.getAction() + ":" + step.getActionInput());
            }

            @Override
            public void onActionEnd(ReActStep step, Object result) {
                System.out.println("工具调用结束 >>>>> " + step.getAction() + ":" + step.getActionInput() + ">>>>结果：" + result);
            }

            @Override
            public void onFinalAnswer(String finalAnswer) {
                System.out.println("onFinalAnswer >>>>>" + finalAnswer);
                ChatMemory memory = reActAgent.getHistoriesPrompt().getMemory();
                System.out.println(memory);
            }

            @Override
            public void onNonActionResponseStream(StreamContext context) {
                System.out.println("onNonActionResponseStream >>>>>" + context);
            }
        });

        reActAgent.execute();

        TimeUnit.SECONDS.sleep(30);
    }


}
