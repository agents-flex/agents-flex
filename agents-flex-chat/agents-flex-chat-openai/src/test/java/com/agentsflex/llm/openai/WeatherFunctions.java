package com.agentsflex.llm.openai;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

public class WeatherFunctions {

    private static int currentIndex = 0;
    private static String[] weathers = {"阴", "雨", "雪", "晴"};

    @ToolDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(@ToolParam(name = "city", description = "the city name") String name) {

        System.out.println(">>>>>>>>>>>>>>!!!!!!" + name);
        if (currentIndex >= weathers.length) {
            currentIndex = 0;
        }
        return weathers[currentIndex++];
    }


//    @FunctionDef(name = "get_city_ip", description = "get the city ip address")
//    public static String getIPAddress(@FunctionParam(name = "city", description = "the city name") String name) {
//        return "127.0.0.1";
//    }
}
