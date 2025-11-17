package com.agentsflex.llm.spark.test;

import com.agentsflex.core.model.chat.functions.annotation.FunctionDef;
import com.agentsflex.core.model.chat.functions.annotation.FunctionParam;

public class WeatherFunctions {

    @FunctionDef(name = "天气查询", description = "获取天气信息的方法")
    public static String getWeatherInfo(
        @FunctionParam(name = "location", description = "城市的名称，比如: 北京, 上海") String name
    ) {
        //此处应该通过 api 去第三方获取
        return name + "今天的天气阴转多云";
    }
}
