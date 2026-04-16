package com.agentsflex.model.chat.openai;

import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

public class City {
    @ToolParam(name = "name", description = "城市名称", required = true)
    private String name;

    @ToolParam(name = "country", description = "国家名称", required = true)
    private String country;
}
