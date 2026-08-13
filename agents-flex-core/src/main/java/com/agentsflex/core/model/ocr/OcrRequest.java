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
package com.agentsflex.core.model.ocr;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * OCR 识别请求。
 *
 * <p>输入支持远程 URL 和本地文件两种形式，但一次请求必须且只能选择其中一种。
 * {@code options} 用于承载供应商特有参数，公共抽象不会解释或修改这些参数。</p>
 */
public class OcrRequest {
    /**
     * 本次请求指定的模型；为空时由供应商配置提供默认值。
     */
    private String model;
    /**
     * 可被供应商直接下载的文件地址。
     */
    private String fileUrl;
    /**
     * 需要上传给供应商的本地文件。
     */
    private File file;
    /**
     * 显式文件名；为空时会回退到本地文件名。
     */
    private String fileName;
    /**
     * 供应商特有的请求选项。
     */
    private Map<String, Object> options;

    /**
     * 创建远程 URL 请求。
     *
     * @param fileUrl 供应商可访问的文件地址
     * @return 新的 OCR 请求
     */
    public static OcrRequest ofUrl(String fileUrl) {
        OcrRequest request = new OcrRequest();
        request.setFileUrl(fileUrl);
        return request;
    }

    /**
     * 创建本地文件请求。
     *
     * @param file 待上传文件
     * @return 新的 OCR 请求
     */
    public static OcrRequest ofFile(File file) {
        OcrRequest request = new OcrRequest();
        request.setFile(file);
        return request;
    }

    /**
     * 返回本次请求指定的模型。
     */
    public String getModel() {
        return model;
    }

    /**
     * 设置本次请求使用的模型。
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 返回远程文件地址。
     */
    public String getFileUrl() {
        return fileUrl;
    }

    /**
     * 设置远程文件地址。
     */
    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    /**
     * 返回待上传的本地文件。
     */
    public File getFile() {
        return file;
    }

    /**
     * 设置待上传的本地文件。
     */
    public void setFile(File file) {
        this.file = file;
    }

    /**
     * 返回显式文件名；未设置时回退到本地文件自身的名称。
     */
    public String getFileName() {
        return fileName != null ? fileName : file == null ? null : file.getName();
    }

    /**
     * 设置发送给供应商的文件名。
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * 返回只读的供应商选项视图。
     *
     * @return 非空且不可直接修改的选项 Map
     */
    public Map<String, Object> getOptions() {
        return options == null ? Collections.emptyMap() : Collections.unmodifiableMap(options);
    }

    /**
     * 设置供应商选项，并通过防御性复制隔离调用方后续修改。
     */
    public void setOptions(Map<String, Object> options) {
        this.options = options == null ? null : new HashMap<>(options);
    }

    /**
     * 添加或覆盖一个供应商选项。
     *
     * @param key   选项名称
     * @param value 选项值
     */
    public void putOption(String key, Object value) {
        if (options == null) options = new HashMap<>();
        options.put(key, value);
    }
}
