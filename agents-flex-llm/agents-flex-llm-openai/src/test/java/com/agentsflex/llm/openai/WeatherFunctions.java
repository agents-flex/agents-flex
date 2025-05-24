package com.agentsflex.llm.openai;

import com.agentsflex.core.llm.functions.annotation.FunctionDef;
import com.agentsflex.core.llm.functions.annotation.FunctionParam;

public class WeatherFunctions {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo( @FunctionParam(name = "city", description = "the city name") String name) {
        return "阴转多云";
    }


    @FunctionDef(name = "get_city_ip", description = "get the city ip address")
    public static String getIPAddress( @FunctionParam(name = "city", description = "the city name") String name) {
        return "127.0.0.1";
    }
}
