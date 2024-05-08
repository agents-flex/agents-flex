package com.agentsflex.samples.agent;

import com.agentsflex.agent.Output;
import com.agentsflex.llm.Llm;
import com.agentsflex.llm.openai.OpenAiLlm;
import com.agentsflex.llm.openai.OpenAiLlmConfig;

import java.util.HashMap;
import java.util.Map;

public class LlmAgentSample {

    public static void main(String[] args) {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-alQ9N********");
        config.setEndpoint("https://api.moonshot.cn");
        config.setModel("moonshot-v1-8k");

        Llm llm = new OpenAiLlm(config);

        SampleLlmAgent agent = new SampleLlmAgent(llm);

        String ddlInfo = "表名 student，字段 id,name";

        Map<String, Object> variables = new HashMap<>();
        variables.put("ddlInfo", ddlInfo);

        Output output = agent.execute(variables, null);
        System.out.println(output.getValue());
    }
}
