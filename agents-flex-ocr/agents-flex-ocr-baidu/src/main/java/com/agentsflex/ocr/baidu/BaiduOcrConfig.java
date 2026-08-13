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
package com.agentsflex.ocr.baidu;

import com.agentsflex.core.model.ocr.BaseOcrConfig;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 百度 PaddleOCR-VL 文档解析服务配置。
 *
 * <p>百度同时存在两种鉴权形式：旧 OAuth access token 作为 URL 查询参数传递，
 * {@code bce-v3/} 开头的新版 API Key 则通过 Bearer 请求头传递。本类根据凭证格式
 * 自动选择方式，使提交和查询请求使用一致的鉴权协议。</p>
 */
public class BaiduOcrConfig extends BaseOcrConfig {
    /**
     * 使用百度线上接口、PaddleOCR 默认模型及五秒查询间隔创建配置。
     */
    public BaiduOcrConfig() {
        setProvider("baidu");
        setEndpoint("https://aip.baidubce.com");
        setRequestPath("/rest/2.0/brain/online/v2/paddle-vl-parser/task");
        setQueryPath("/rest/2.0/brain/online/v2/paddle-vl-parser/task/query");
        setModel(BaiduOcrModels.PADDLE_OCR_VL_1_6);
        setPollIntervalMillis(5_000L);
    }

    /** 返回任务提交地址；旧版 access token 会附加到查询参数。 */
    @Override
    public String getFullUrl() {
        return usesBearerAuthorization() ? super.getFullUrl() : withAccessToken(super.getFullUrl());
    }

    /**
     * 返回任务查询地址；旧版 access token 会附加到查询参数。
     */
    public String getQueryUrl() {
        String url = getEndpoint() + getQueryPath();
        return usesBearerAuthorization() ? url : withAccessToken(url);
    }

    /** 判断当前凭证是否为百度新版 bce-v3 Bearer API Key。 */
    public boolean usesBearerAuthorization() {
        return getApiKey() != null && getApiKey().startsWith("bce-v3/");
    }

    /**
     * 对 token 做 UTF-8 表单编码，避免空格等字符破坏 URL 查询串。
     */
    private String withAccessToken(String url) {
        try {
            return url + "?access_token=" + URLEncoder.encode(getApiKey() == null ? "" : getApiKey(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Java 运行时必须支持 UTF-8；若环境异常则转为不可恢复的配置错误。
            throw new IllegalStateException(e);
        }
    }
}
