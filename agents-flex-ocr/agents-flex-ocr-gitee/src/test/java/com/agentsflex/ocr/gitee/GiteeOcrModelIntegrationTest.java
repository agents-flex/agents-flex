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
package com.agentsflex.ocr.gitee;

import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResource;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Gitee OCR 真实服务集成测试。
 *
 * <p>该测试会消耗真实供应商配额，只有显式设置 {@code GITEE_OCR_API_KEY} 时才执行。
 * API Key 不写入源码、测试报告或日志。测试图片在临时目录动态生成，测试结束后删除。</p>
 */
public class GiteeOcrModelIntegrationTest {
    /**
     * 真实任务最长等待两分钟，避免供应商异常时无限占用构建线程。
     */
    private static final long TIMEOUT_MILLIS = 120_000L;
    /**
     * 集成测试采用两秒查询间隔，兼顾完成速度和供应商查询 QPS。
     */
    private static final long POLL_INTERVAL_MILLIS = 2_000L;

    private File sampleImage;

    /**
     * 在临时目录生成包含稳定可识别文本的 PNG，不依赖仓库外部测试资源。
     */
    @Before
    public void createSampleImage() throws Exception {
        sampleImage = Files.createTempFile("agents-flex-gitee-ocr-", ".png").toFile();
        BufferedImage image = new BufferedImage(1200, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
            graphics.drawString("AgentsFlex OCR Integration Test", 60, 140);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 48));
            graphics.drawString("Task ID: 2026", 60, 235);
        } finally {
            graphics.dispose();
        }
        assertTrue("临时 OCR 图片写入失败", ImageIO.write(image, "png", sampleImage));
    }

    /**
     * 删除包含测试内容的临时图片，避免集成测试在机器上残留文件。
     */
    @After
    public void deleteSampleImage() throws Exception {
        if (sampleImage != null) Files.deleteIfExists(sampleImage.toPath());
    }

    /**
     * 真实提交图片并轮询至成功，验证鉴权、multipart 上传、状态映射和结果解析全链路。
     */
    @Test
    public void shouldRecognizeImageThroughRealGiteeApi() {
        String apiKey = System.getenv("GITEE_OCR_API_KEY");
        Assume.assumeTrue("未设置 GITEE_OCR_API_KEY，跳过真实 Gitee OCR 测试",
            apiKey != null && !apiKey.trim().isEmpty());

        GiteeOcrConfig config = new GiteeOcrConfig();
        config.setApiKey(apiKey);
        GiteeOcrModel model = new GiteeOcrModel(config);

        OcrResponse response = model.recognizeAndWait(
            OcrRequest.ofFile(sampleImage), TIMEOUT_MILLIS, POLL_INTERVAL_MILLIS);

        assertNotNull("供应商不应返回空响应", response);
        assertFalse("真实 OCR 请求失败: " + response.getErrorCode() + " / " + response.getErrorMessage(),
            response.isError());
        assertEquals("真实 OCR 任务未成功完成", OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertNotNull("供应商成功响应必须保留任务编号", response.getTaskId());
        assertTrue("成功结果应包含内联文本、Markdown 或可下载资源",
            hasText(response.getText()) || hasText(response.getMarkdown()) || hasValidResource(response));
    }

    /**
     * 判断响应中是否至少包含一个类型和 URL 都有效的外部资源。
     */
    private static boolean hasValidResource(OcrResponse response) {
        for (OcrResource resource : response.getResources()) {
            if (resource != null && hasText(resource.getType()) && hasText(resource.getUrl())) return true;
        }
        return false;
    }

    /**
     * 判断文本是否包含非空白内容。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

}
