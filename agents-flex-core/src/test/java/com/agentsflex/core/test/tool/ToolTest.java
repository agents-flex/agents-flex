package com.agentsflex.core.test.tool;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;

public class ToolTest {

    public static void main(String[] args1) {
        Tool getWeather = Tool.builder()
            .name("getWeather")
            .description("查询指定城市某天的天气")
            .addParameter(Parameter.builder()
                .name("city")
                .type("string")
                .description("城市名称")
                .required(true)
                .build())
            .function(args -> {
                String city = (String) args.get("city");
                return city + " 晴，22°C"; // 简化实现
            })
            .build();
    }
}
