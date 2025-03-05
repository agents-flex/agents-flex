package com.agentsflex.image.volcengine;

import com.agentsflex.core.image.*;
import com.agentsflex.core.llm.client.HttpClient;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.volcengine.service.visual.IVisualService;
import com.volcengine.service.visual.impl.VisualServiceImpl;

public class VolcengineImageModel {

    private VolcengineImageModelConfig config;
    private HttpClient httpClient = new HttpClient();

    public VolcengineImageModel(VolcengineImageModelConfig config) {
        this.config = config;
    }

    /**
     * 生成图片，采用sdk方式,调用同步接口
     * @author <wangyangyang>
     * @param req
     * @return
     */
    public String generateVolcengineImage(JSONObject req) {
        IVisualService visualService = VisualServiceImpl.getInstance();

        visualService.setAccessKey(config.getAccessKey());
        visualService.setSecretKey(config.getSecretKey());

        Object response = null;
        try {
            response = visualService.cvProcess(req);
            return JSON.toJSONString(response);
        } catch(Exception e) {
            throw new RuntimeException("图像生成过程中发生错误",e);
        }
    }

}
