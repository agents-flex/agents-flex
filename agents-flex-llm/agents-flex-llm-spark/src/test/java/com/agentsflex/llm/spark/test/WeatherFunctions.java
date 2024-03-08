package com.agentsflex.llm.spark.test;

import com.agentsflex.functions.annotation.FunctionDef;
import com.agentsflex.functions.annotation.FunctionParam;

public class WeatherFunctions {

    @FunctionDef(name = "weather", description = "获取天气信息")
    public static String weather(
        @FunctionParam(name = "location", description = "城市名称，比如: 北京, 上海") String name
    ) {
        //此处应该通过 api 去第三方获取
        return name + "今天的天气阴转多云";
    }
}
