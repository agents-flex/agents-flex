package com.agentsflex.core.model.chat.tool;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProviderTool extends BaseTool {

    public static ProviderTool of(String name) {
        ProviderTool tool = new ProviderTool();
        tool.setName(name);
        return tool;
    }

    public static List<ProviderTool> ofList(String... names) {
        List<ProviderTool> tools = new ArrayList<>();
        for (String name : names) {
            tools.add(of(name));
        }
        return tools;
    }

    @Override
    public Object invoke(Map<String, Object> argsMap) {
        return null;
    }
}
