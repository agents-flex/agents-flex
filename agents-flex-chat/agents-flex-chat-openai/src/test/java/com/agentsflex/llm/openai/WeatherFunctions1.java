package com.agentsflex.llm.openai;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

import java.util.concurrent.ThreadLocalRandom;

public class WeatherFunctions1 {

    private static final String[] weathers = {
        "晴", "多云", "阴", "小雨", "中雨", "大雨", "暴雨", "雷阵雨",
        "小雪", "中雪", "大雪", "暴雪", "雨夹雪", "雾", "霾", "沙尘暴",
        "冰雹", "阵雨", "冻雨", "晴间多云", "局部多云", "强对流"
    };

    @ToolDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(@ToolParam(name = "location", description = "the location") Location location) {
        String weather = weathers[ThreadLocalRandom.current().nextInt(weathers.length)];
        System.out.println(">>>>>>>>>>>>>>!!!!!!" + location.getCity() + ":" + weather);
        return weather;
    }

}
