package com.agentsflex.core.model.chat.tool;


import java.util.Map;

public class ProviderTool extends BaseTool {

    public static ProviderTool of(String name) {
        ProviderTool tool = new ProviderTool();
        tool.setName(name);
        return tool;
    }

    @Override
    public Object invoke(Map<String, Object> argsMap) {
        return null;
    }
}
