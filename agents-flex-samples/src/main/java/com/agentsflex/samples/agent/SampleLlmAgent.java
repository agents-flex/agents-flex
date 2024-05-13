package com.agentsflex.samples.agent;

import com.agentsflex.agent.LLMAgent;
import com.agentsflex.agent.Output;
import com.agentsflex.llm.Llm;
import com.agentsflex.message.AiMessage;

public class SampleLlmAgent extends LLMAgent {

    public SampleLlmAgent(Llm llm) {
        this.llm = llm;
        this.prompt = "您现在是一个 MySQL 数据库架构师，请根据如下的表结构信息，" +
            "帮我生成可以执行的 DDL 语句，以方便我用于创建 MySQL 的表结构。\n" +
            "注意：\n" +
            "请直接返回 DDL 内容，不需要解释，不需要以及除了 DDL 语句以外的其他内容。\n" +
            "\n以下是表信息的内容：\n\n{ddlInfo}";
    }

    @Override
    protected Output onMessage(AiMessage aiMessage) {
        String sqlContent = aiMessage.getContent()
            .replace("```sql", "")
            .replace("```", "");
        return Output.ofDefault(sqlContent);
    }
}
