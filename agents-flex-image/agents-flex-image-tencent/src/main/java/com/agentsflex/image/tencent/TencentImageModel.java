/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.image.tencent;

import com.agentsflex.core.image.*;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.tencentcloudapi.common.AbstractModel;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.hunyuan.v20230901.HunyuanClient;
import com.tencentcloudapi.hunyuan.v20230901.models.QueryHunyuanImageJobRequest;
import com.tencentcloudapi.hunyuan.v20230901.models.QueryHunyuanImageJobResponse;
import com.tencentcloudapi.hunyuan.v20230901.models.SubmitHunyuanImageJobRequest;
import com.tencentcloudapi.hunyuan.v20230901.models.SubmitHunyuanImageJobResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

public class TencentImageModel implements ImageModel {
    private static final Logger LOG = LoggerFactory.getLogger(TencentImageModel.class);
    private final TencentImageModelConfig config;

    public TencentImageModel(TencentImageModelConfig config) {
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        try {
            SubmitHunyuanImageJobResponse resp = getSubmitHunyuanImageJobResponse(request);
            // 输出json格式的字符串回包
            String respJson = AbstractModel.toJsonString(resp);
            Map<String, Object> resultMap = AbstractModel.fromJsonString(respJson, Maps.class);
            if (Objects.isNull(resultMap.get("JobId"))) {
                return ImageResponse.error("response is no text");
            }
            String jobId = (String) resultMap.get("JobId");
            return getImage(jobId);
        } catch (TencentCloudSDKException e) {
            return ImageResponse.error(e.toString());
        }
    }

    @Override
    public ImageResponse edit(EditImageRequest request) {
        throw new IllegalStateException("TencentImageModel Can not support edit image.");
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        throw new IllegalStateException("TencentImageModel Can not support vary image.");
    }

    private SubmitHunyuanImageJobResponse getSubmitHunyuanImageJobResponse(GenerateImageRequest request) throws TencentCloudSDKException {
        HunyuanClient client = getHunyuanClient();
        SubmitHunyuanImageJobRequest req = new SubmitHunyuanImageJobRequest();
        //设置参数
        req.setPrompt(request.getPrompt());
        req.setNegativePrompt(request.getNegativePrompt());
        req.setStyle(request.getStyle());
        //生成图分辨率
        req.setResolution(request.getQuality());
        //图片生成数量
        if (null != request.getN()) {
            req.setNum(request.getN().longValue());
        }
        // 返回的resp是一个SubmitHunyuanImageJobResponse的实例，与请求对象对应
        return client.SubmitHunyuanImageJob(req);
    }

    private static final Object LOCK = new Object();

    private ImageResponse getImage(String jobId) {
        ImageResponse imageResponse = null;
        while (true) {
            synchronized (LOCK) {
                imageResponse = callService(jobId);
                if (!Objects.isNull(imageResponse)) {
                    break;
                }
                // 等待一段时间再重试
                try {
                    LOCK.wait(1000);
                } catch (InterruptedException e) {
                    // 线程在等待时被中断
                    Thread.currentThread().interrupt();
                    imageResponse = ImageResponse.error(e.toString());
                    break;
                }
            }
        }
        return imageResponse;
    }


    public ImageResponse callService(String jobId) {
        try {
            HunyuanClient client = getHunyuanClient();
            // 实例化一个请求对象,每个接口都会对应一个request对象
            QueryHunyuanImageJobRequest req = new QueryHunyuanImageJobRequest();
            req.setJobId(jobId);
            QueryHunyuanImageJobResponse resp = client.QueryHunyuanImageJob(req);
            // 输出json格式的字符串回包
            String respJson = AbstractModel.toJsonString(resp);
            JSONObject resultJson = JSONObject.parseObject(respJson);
//            LOG.info("返回结果状态：：：：{}",resultJson.get("JobStatusCode"));
            if (Objects.isNull(resultJson.get("JobStatusCode"))) {
                return null;
            }
            Integer jobStatusCode = resultJson.getInteger("JobStatusCode");
            if (Objects.equals(5, jobStatusCode)) {
                //处理完成
                if (Objects.isNull(resultJson.get("ResultImage"))) {
                    return ImageResponse.error("response is no ResultImage");
                }
                JSONArray imagesArray = resultJson.getJSONArray("ResultImage");
                ImageResponse response = new ImageResponse();
                for (int i = 0; i < imagesArray.size(); i++) {
                    String imageObj = imagesArray.getString(i);
                    response.addImage(imageObj);
                }
                return response;
            }
            if (Objects.equals(4, jobStatusCode)) {
                //处理错误
                return ImageResponse.error(resultJson.getString("JobErrorMsg"));
            }
        } catch (TencentCloudSDKException e) {
            return ImageResponse.error(e.toString());
        }
        return null;
    }

    private HunyuanClient getHunyuanClient() {
        Credential cred = new Credential(config.getSecretId(), config.getSecretKey());
        // 实例化一个http选项，可选的，没有特殊需求可以跳过
        HttpProfile httpProfile = new HttpProfile();
        // 实例化一个client选项，可选的，没有特殊需求可以跳过
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        // 实例化要请求产品的client对象,clientProfile是可选的
        return new HunyuanClient(cred, config.getRegion(), clientProfile);
    }

}
