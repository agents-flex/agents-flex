package com.agentsflex.llm.ollama;

import com.agentsflex.core.functions.annotation.FunctionDef;
import com.agentsflex.core.functions.annotation.FunctionParam;

public class WeatherFunctions {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        return "Today it will be dull and overcast in " + name;
    }


    @FunctionDef(name = "get_the_temperature", description = "get the temperature")
    public static String getTemperature(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        return "The temperature in " + name + " is 15°C";
    }
}
