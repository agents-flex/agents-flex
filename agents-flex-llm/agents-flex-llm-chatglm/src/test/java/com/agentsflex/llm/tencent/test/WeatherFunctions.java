package com.agentsflex.llm.tencent.test;

import com.agentsflex.core.llm.functions.annotation.FunctionDef;
import com.agentsflex.core.llm.functions.annotation.FunctionParam;
import com.agentsflex.core.llm.client.HttpClient;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;

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
