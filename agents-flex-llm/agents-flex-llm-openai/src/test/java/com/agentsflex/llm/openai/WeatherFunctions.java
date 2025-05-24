package com.agentsflex.llm.openai;

import com.agentsflex.core.llm.functions.annotation.FunctionDef;
import com.agentsflex.core.llm.functions.annotation.FunctionParam;

public class WeatherFunctions {

    private static int currentIndex = 0;
    private static String[] weathers = {"阴", "雨", "雪", "晴"};

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(@FunctionParam(name = "city", description = "the city name") String name) {
        if (currentIndex >= weathers.length) {
            currentIndex = 0;
        }
        return weathers[currentIndex++];
    }


    @FunctionDef(name = "get_city_ip", description = "get the city ip address")
    public static String getIPAddress(@FunctionParam(name = "city", description = "the city name") String name) {
        return "127.0.0.1";
    }
}
