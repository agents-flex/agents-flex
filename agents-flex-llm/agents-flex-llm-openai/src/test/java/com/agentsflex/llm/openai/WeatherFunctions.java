package com.agentsflex.llm.openai;

import com.agentsflex.core.functions.annotation.FunctionDef;
import com.agentsflex.core.functions.annotation.FunctionParam;

public class WeatherFunctions {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        return "Today it will be dull and overcast in " + name;
    }
}
