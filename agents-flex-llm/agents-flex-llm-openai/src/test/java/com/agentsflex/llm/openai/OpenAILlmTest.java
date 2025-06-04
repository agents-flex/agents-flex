package com.agentsflex.llm.openai;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.exception.LlmException;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.prompt.ToolPrompt;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class OpenAILlmTest {

    @Test(expected = LlmException.class)
    public void testChat() {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        Llm llm = new OpenAILlm(config);
        String response = llm.chat("请问你叫什么名字");

        System.out.println(response);
    }

    @Test()
    public void testChatStream() {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey("sk-alQ9N********");
        config.setEndpoint("https://api.moonshot.cn");
        config.setModel("moonshot-v1-8k");
//        config.setDebug(true);

        Llm llm = new OpenAILlm(config);
        llm.chatStream("你叫什么名字", new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
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
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
//        config.setDebug(true);

        Llm llm = new OpenAILlm(config);
        llm.chatStream("who are you", new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println(response.getMessage().getContent());
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
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey("sk-5gqOcl*****");
        config.setModel("gpt-4-turbo");


        Llm llm = new OpenAILlm(config);
        ImagePrompt prompt = new ImagePrompt("What's in this image?");
        prompt.setImageUrl("https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg");


        AiMessageResponse response = llm.chat(prompt);
        System.out.println(response);
    }


    @Test()
    public void testFunctionCalling1() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
        // 阴转多云
    }

    @Test()
    public void testFunctionCalling2() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        if (response.isFunctionCall()) {
            AiMessageResponse response1 = llm.chat(ToolPrompt.of(response));
            System.out.println(response1.getMessage().getContent());
        } else {
            System.out.println(response);
        }
    }

    @Test()
    public void testFunctionCalling3() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setDebug(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setChatPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d57a");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        if (response.isFunctionCall()) {
            AiMessageResponse response1 = llm.chat(ToolPrompt.of(response));
            System.out.println(response1.getMessage().getContent());
        } else {
            System.out.println(response);
        }
    }

    @Test()
    public void testFunctionCalling4() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setDebug(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setChatPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d57aa75");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println(" onMessage >>>>>" + response.isFunctionCall());
            }
        });

        TimeUnit.SECONDS.sleep(5);
    }

    @Test()
    public void testFunctionCalling44() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setDebug(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setChatPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d5");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("北京和上海的天气怎么样", WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println(" onMessage >>>>>" + response.isFunctionCall());
            }
        });

        TimeUnit.SECONDS.sleep(5);
    }

    @Test()
    public void testFunctionCalling444() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setDebug(true);
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setChatPath("/api/v3/chat/completions");
        config.setModel("deepseek-v3-250324");
        config.setApiKey("2d57");

        OpenAILlm llm = new OpenAILlm(config);


        FunctionPrompt prompt = new FunctionPrompt("北京和上海的天气怎么样", WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {


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
                    llm.chatStream(ToolPrompt.of(response), new StreamResponseListener() {
                        @Override
                        public void onMessage(ChatContext context, AiMessageResponse response) {
                            String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                            System.out.println(":::"+msg);
                        }
                    });
                } else {
                    String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                    System.out.println(">>>"+msg);
                }
            }
        });

        TimeUnit.SECONDS.sleep(25);

    }


    @Test()
    public void testFunctionCalling5() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setDebug(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen3-32B");
        config.setApiKey("PXW1");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("北京和上海的天气怎么样", WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println("onMessage >>>>>" + response);
            }
        });

        TimeUnit.SECONDS.sleep(5);
    }


    @Test()
    public void testFunctionCalling55() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setDebug(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen3-32B");
        config.setApiKey("PXW1");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("/no_think 北京和上海的天气怎么样", WeatherFunctions.class);
//        FunctionPrompt prompt = new FunctionPrompt("上海的天气怎么样", WeatherFunctions.class);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
//                System.out.println("onMessage >>>>>" + response);
                if (response.isFunctionCall()) {
                    System.out.println(":::::::: start....");
                    llm.chatStream(ToolPrompt.of(response), new StreamResponseListener() {
                        @Override
                        public void onMessage(ChatContext context, AiMessageResponse response) {
                            String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                            System.out.println(":::"+msg);
                        }
                    });
                } else {
                    String msg = response.getMessage().getContent() != null ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                    System.out.println(">>>"+msg);
                }
            }
        });

        TimeUnit.SECONDS.sleep(25);
    }

    @Test()
    public void testFunctionCalling6() throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setDebug(true);
        config.setEndpoint("https://ai.gitee.com");
        config.setModel("Qwen3-32B");
        config.setApiKey("PXW1");

        OpenAILlm llm = new OpenAILlm(config);

        FunctionPrompt prompt = new FunctionPrompt("/nothink 北京和上海的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(llm.chat(ToolPrompt.of(response)));

    }
}
