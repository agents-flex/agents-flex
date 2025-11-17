package com.agentsflex.llm.chatglm.test;

import com.agentsflex.core.model.chat.functions.annotation.FunctionDef;
import com.agentsflex.core.model.chat.functions.annotation.FunctionParam;
import com.agentsflex.core.model.client.HttpClient;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.serializer.SerializerFeature;

public class WeatherFunctions {

    private static String getCityCode(String cityName) {
        return cityName.contains("北京") ? "101010100" : null;
    }

    @FunctionDef(name = "天气查询", description = "获取天气信息的方法")
    public static String getWeatherInfo(
        @FunctionParam(name = "location", description = "城市的名称，比如: 北京, 上海") String name
    ) {
        String response = new HttpClient().get("http://t.weather.sojson.com/api/weather/city/" + getCityCode(name));
        JSONObject jsonObject = JSONObject.parseObject(response);
        return JSON.toJSONString(jsonObject, SerializerFeature.PrettyFormat, SerializerFeature.WriteMapNullValue);
    }
}
