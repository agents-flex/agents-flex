package com.agentsflex.llm.vllm.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.HumanImageMessage;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.llm.vllm.VLlmLlm;
import com.agentsflex.llm.vllm.VLlmLlmConfig;
import org.junit.Test;

public class VLlmTest {

    public static void main(String[] args) throws InterruptedException {
        VLlmLlmConfig config = new VLlmLlmConfig();

        //https://docs.vllm.ai/en/latest/api/inference_params.html
        config.setApiKey("*************************************");
        config.setModel("qwen2.5-vl-7b");
        Llm llm = new VLlmLlm(config);
        HistoriesPrompt prompt = new HistoriesPrompt();
        ImagePrompt imagePrompt = new ImagePrompt("这个图片干什么的");
        imagePrompt.addImageUrl("https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg");
        prompt.addMessage(new HumanImageMessage(imagePrompt));

        llm.chatStream(prompt, (context, response) -> {
            AiMessage message = response.getMessage();
            LogUtil.println(">>>> " + message.getContent());
        });

        Thread.sleep(10000);
    }


    @Test
    public void testFunctionCalling() throws InterruptedException {
        VLlmLlmConfig config = new VLlmLlmConfig();
        config.setApiKey("*****************");

        Llm llm = new VLlmLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
        // "Today it will be dull and overcast in 北京"
    }


    @Test
    public void testEmbedding() throws InterruptedException {
        VLlmLlmConfig config = new VLlmLlmConfig();
        config.setApiKey("********************");
        Llm llm = new VLlmLlm(config);
        VectorData vectorData = llm.embed(Document.of("test"));
        System.out.println(vectorData);
    }

}
