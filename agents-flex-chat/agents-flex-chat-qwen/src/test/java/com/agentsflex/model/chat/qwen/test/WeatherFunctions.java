package com.agentsflex.model.chat.qwen.test;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

public class WeatherFunctions {

    @ToolDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @ToolParam(name = "city", description = "the city name") String name
    ) {
        return "Today it will be dull and overcast in " + name;
    }
}
