package com.agentsflex.llm.ollama;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

public class WeatherFunctions {

    @ToolDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @ToolParam(name = "city", description = "the city name") String name
    ) {
        return "Snowy days";
    }


    @ToolDef(name = "get_the_temperature", description = "get the temperature")
    public static String getTemperature(
        @ToolParam(name = "city", description = "the city name") String name
    ) {
        return "The temperature in " + name + " is 15°C";
    }
}
