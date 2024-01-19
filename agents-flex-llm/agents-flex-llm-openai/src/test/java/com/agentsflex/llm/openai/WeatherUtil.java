package com.agentsflex.llm.openai;

import com.agentsflex.functions.annotation.FunctionDef;
import com.agentsflex.functions.annotation.FunctionParam;

public class WeatherUtil {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        return "Today it will be dull and overcast in city " + name;
    }
}
