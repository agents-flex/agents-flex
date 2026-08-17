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
package com.agentsflex.ocr.mineru;

import com.alibaba.fastjson2.JSON;
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
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * MinerU OCR 真实服务集成测试。
 *
 * <p>测试完整覆盖申请预签名 URL、PUT 上传本地文件、使用 batch ID 查询结果的链路。
 * 显式设置 {@code MINERU_OCR_TOKEN}，或同时设置 {@code OPENXLAB_ACCESS_KEY_ID} 与
 * {@code OPENXLAB_SECRET_ACCESS_KEY} 时执行，凭据不会写入源码或测试输出。</p>
 */
public class MineruOcrModelIntegrationTest {
    /**
     * MinerU 文档解析可能排队，真实任务最多等待三分钟。
     */
    private static final long TIMEOUT_MILLIS = 600_000L;
    /**
     * 使用三秒查询间隔控制真实查询请求频率。
     */
    private static final long POLL_INTERVAL_MILLIS = 3_000L;

    private static final Path PDF_SAMPLE = Paths.get("..", "..", "testresource", "2305.03393v1.pdf");
    private static final Path PDF_OUTPUT = Paths.get("target", "mineru-ocr-integration", "2305.03393v1");

    private File sampleImage;

    /**
     * 动态生成包含稳定英文文本的 PNG，避免提交仓库测试资源。
     */
    @Before
    public void createSampleImage() throws Exception {
        sampleImage = Files.createTempFile("agents-flex-mineru-ocr-", ".png").toFile();
        BufferedImage image = new BufferedImage(1200, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
            graphics.drawString("AgentsFlex MinerU Integration", 45, 140);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 48));
            graphics.drawString("OCR Task 2026", 45, 235);
        } finally {
            graphics.dispose();
        }
        assertTrue("临时 OCR 图片写入失败", ImageIO.write(image, "png", sampleImage));
    }

    /**
     * 删除真实测试使用的临时图片。
     */
    @After
    public void deleteSampleImage() throws Exception {
        if (sampleImage != null) Files.deleteIfExists(sampleImage.toPath());
    }

    /**
     * 上传本地图片并轮询批任务到成功，验证 MinerU 本地文件完整链路。
     */
    @Test
    public void shouldRecognizeLocalImageThroughRealMineruApi() {
        MineruOcrConfig config = integrationConfig();
        Assume.assumeNotNull(config);
        MineruOcrModel model = new MineruOcrModel(config);

        OcrResponse response = model.recognizeAndWait(
            OcrRequest.ofFile(sampleImage), TIMEOUT_MILLIS, POLL_INTERVAL_MILLIS);

        assertNotNull("供应商不应返回空响应", response);
        assertFalse("真实 MinerU 请求失败: " + response.getErrorCode() + " / " + response.getErrorMessage(),
            response.isError());
        assertEquals("真实 MinerU 任务未成功完成", OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertNotNull("成功响应必须保留 task ID 或 batch ID", response.getTaskId());
        assertTrue("成功结果应包含 Markdown 或至少一个有效下载资源",
            hasText(response.getMarkdown()) || hasValidResource(response));
    }

    /**
     * 使用仓库中的真实 PDF 验证 MinerU 上传、轮询、ZIP Markdown 解析及图片处理全链路。
     * 结果保存在 target/mineru-ocr-integration，便于人工检查识别质量。
     */
    @Test
    public void shouldRecognizePdfAndMaterializeMarkdownAndImages() throws Exception {
        MineruOcrConfig config = integrationConfig();
        Assume.assumeNotNull(config);
        Assume.assumeTrue("缺少 PDF 测试文件: " + PDF_SAMPLE.toAbsolutePath(), Files.isRegularFile(PDF_SAMPLE));

        deleteRecursively(PDF_OUTPUT);
        Files.createDirectories(PDF_OUTPUT);
        Path imageOutput = PDF_OUTPUT.resolve("images");
        Files.createDirectories(imageOutput);
        AtomicInteger imageCount = new AtomicInteger();

        MineruOcrModel model = new MineruOcrModel(config);
        model.setExtractedImageHandler((imageBytes, mimeType, fileName) -> {
            int index = imageCount.incrementAndGet();
            String originalName = fileName == null ? "image" : Paths.get(fileName).getFileName().toString();
            int extensionIndex = originalName.lastIndexOf('.');
            String baseName = extensionIndex > 0 ? originalName.substring(0, extensionIndex) : originalName;
            String extension = "image/png".equals(mimeType) ? ".png" :
                ("image/jpeg".equals(mimeType) ? ".jpg" : ".bin");
            Path destination = imageOutput.resolve(String.format("%03d-%s%s", index, baseName, extension));
            Files.copy(new java.io.ByteArrayInputStream(imageBytes), destination,
                StandardCopyOption.REPLACE_EXISTING);
            return destination.toAbsolutePath().toUri().toString();
        });

        OcrRequest request = OcrRequest.ofFile(PDF_SAMPLE.toFile());
        request.putOption("is_ocr", true);
        request.putOption("formula_enable", true);
        request.putOption("table_enable", true);
        OcrResponse response = model.recognizeAndWait(request, TIMEOUT_MILLIS, POLL_INTERVAL_MILLIS);

        assertNotNull("供应商不应返回空响应", response);
        assertFalse("真实 PDF OCR 请求失败: " + response.getErrorCode() + " / " + response.getErrorMessage(),
            response.isError());
        assertEquals("真实 PDF OCR 任务未成功完成", OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertTrue("PDF OCR 应直接返回已物化的 Markdown", hasText(response.getMarkdown()));
        assertFalse("Markdown 不应保留 Base64 图片", response.getMarkdown().contains("data:image/"));

        Files.write(PDF_OUTPUT.resolve("result.md"), response.getMarkdown().getBytes(StandardCharsets.UTF_8));
        String summary = "taskId=" + response.getTaskId() + System.lineSeparator()
            + "status=" + response.getStatus() + System.lineSeparator()
            + "markdownChars=" + response.getMarkdown().length() + System.lineSeparator()
            + "handledImages=" + imageCount.get() + System.lineSeparator()
            + "resources=" + response.getResources() + System.lineSeparator();
        Files.write(PDF_OUTPUT.resolve("response.txt"), summary.getBytes(StandardCharsets.UTF_8));
        int rawArtifactCount = saveRawArtifacts(response, PDF_OUTPUT.resolve("raw"));

        assertTrue("样例 PDF 应至少解析出一张图片；结果目录: " + imageOutput.toAbsolutePath(),
            imageCount.get() > 0);
        assertTrue("供应商原始结果应保存到 raw 目录", rawArtifactCount > 0);
        assertTrue("Markdown 应引用 ExtractedImageHandler 生成的本地图片 URL",
            response.getMarkdown().contains(imageOutput.toAbsolutePath().toUri().toString()));
    }

    /**
     * 判断响应中是否至少存在一个类型与 URL 都有效的结果资源。
     */
    private static boolean hasValidResource(OcrResponse response) {
        for (OcrResource resource : response.getResources()) {
            if (resource != null && hasText(resource.getType()) && hasText(resource.getUrl())) return true;
        }
        return false;
    }

    private static MineruOcrConfig integrationConfig() {
        String token = System.getenv("MINERU_OCR_TOKEN");
        String accessKeyId = System.getenv("OPENXLAB_ACCESS_KEY_ID");
        String secretAccessKey = System.getenv("OPENXLAB_SECRET_ACCESS_KEY");
        if (!hasText(token) && (!hasText(accessKeyId) || !hasText(secretAccessKey))) return null;
        MineruOcrConfig config = new MineruOcrConfig();
        if (hasText(token)) {
            config.setApiKey(token);
        } else {
            config.setAccessKeyId(accessKeyId);
            config.setSecretAccessKey(secretAccessKey);
        }
        return config;
    }

    private static int saveRawArtifacts(OcrResponse response, Path output) throws Exception {
        Files.createDirectories(output);
        int index = 0;
        for (OcrResource resource : response.getResources()) {
            if (resource == null || !hasText(resource.getUrl())) continue;
            String type = hasText(resource.getType()) ? resource.getType().toLowerCase() : "resource";
            String extension = "archive".equals(type) ? ".zip" :
                ("markdown".equals(type) ? ".md" : ("json".equals(type) ? ".json" : ".bin"));
            Path destination = output.resolve(String.format("%03d-%s%s", ++index, type, extension));
            try (InputStream input = new URL(resource.getUrl()).openStream()) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (index == 0 && !response.isMetadataEmpty()) {
            Files.write(output.resolve(String.format("%03d-provider-response.json", ++index)),
                JSON.toJSONBytes(response.getMetadataMap()));
        }
        if (response.getResources().isEmpty() && hasText(response.getMarkdown())) {
            Files.write(output.resolve(String.format("%03d-inline-markdown.md", ++index)),
                response.getMarkdown().getBytes(StandardCharsets.UTF_8));
        }
        if (response.getResources().isEmpty() && hasText(response.getText())) {
            Files.write(output.resolve(String.format("%03d-inline-text.txt", ++index)),
                response.getText().getBytes(StandardCharsets.UTF_8));
        }
        return index;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) deleteRecursively(child);
            }
        }
        Files.delete(path);
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
