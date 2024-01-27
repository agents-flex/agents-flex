package com.agentsflex.llm.zhipu.test;

import com.agentsflex.llm.Llm;
import com.agentsflex.llm.zhipu.ZhipuLlm;
import com.agentsflex.llm.zhipu.ZhipuLlmConfig;

public class ZhipuTest {

    public static void main(String[] args) {
        ZhipuLlmConfig config = new ZhipuLlmConfig();
        config.setApiKey("f26*****");

        Llm llm = new ZhipuLlm(config);
        String result = llm.chat("你叫什么名字");
        System.out.println(result);
    }
}
