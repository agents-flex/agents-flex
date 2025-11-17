package com.agentsflex.llm.qwen.test;

import com.agentsflex.core.model.chat.functions.annotation.FunctionDef;
import com.agentsflex.core.model.chat.functions.annotation.FunctionParam;

public class WeatherFunctions {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        return "Today it will be dull and overcast in " + name;
    }
}
