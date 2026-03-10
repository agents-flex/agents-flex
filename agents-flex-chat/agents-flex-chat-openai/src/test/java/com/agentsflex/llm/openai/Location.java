

/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.llm.openai;

import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

/**
 * 地理位置信息（嵌套对象）
 *
 * @author fuhai
 * @since 2026/03/10
 */
public class Location {

    @ToolParam(name = "city", description = "城市名称", required = true)
    private String city;

    @ToolParam(name = "province", description = "省份/州")
    private String province;

    @ToolParam(name = "country", description = "国家代码", enums = {"CN", "US", "JP", "UK"})
    private String country;

    // ❌ 无 @ToolParam 注解 → 不会出现在 schema 中
    private String internalGeoHash;

    // =============== Getters and Setters ===============

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getInternalGeoHash() {
        return internalGeoHash;
    }

    public void setInternalGeoHash(String internalGeoHash) {
        this.internalGeoHash = internalGeoHash;
    }

    @Override
    public String toString() {
        return "Location{" +
            "city='" + city + '\'' +
            ", province='" + province + '\'' +
            ", country='" + country + '\'' +
            '}';
    }
}
