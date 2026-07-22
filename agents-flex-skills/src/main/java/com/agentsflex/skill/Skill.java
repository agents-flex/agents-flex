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
package com.agentsflex.skill;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一个已经解析完成的 Skill 定义。
 *
 * <p>每个实例对应一个 {@code SKILL.md} 文件，其中：</p>
 * <ul>
 *     <li>{@code basePath} 是 Skill 根目录，而不是 {@code SKILL.md} 文件本身；</li>
 *     <li>{@code frontMatter} 保存 Markdown 顶部的元数据，例如 {@code name} 和
 *     {@code description}；</li>
 *     <li>{@code content} 保存去掉 front matter 后的完整使用说明。</li>
 * </ul>
 *
 * <p>Skill 被远程 Runtime 准备后，会生成一个内容相同但 {@code basePath} 指向
 * 沙箱内部目录的新实例。调用方因此不应假定 {@code basePath} 一定是本机路径。</p>
 */
public class Skill {

    private String basePath;
    private Map<String, Object> frontMatter;
    private String content;

    /**
     * 创建一个空 Skill，主要用于序列化框架或分步赋值。
     */
    public Skill() {
    }

    /**
     * 创建一个完整的 Skill 定义。
     *
     * @param basePath Skill 根目录；可能是本机目录，也可能是 Runtime 内目录
     * @param frontMatter 从 {@code SKILL.md} 解析出的元数据
     * @param content 去掉 front matter 后的 Markdown 指令正文
     */
    public Skill(String basePath, Map<String, Object> frontMatter, String content) {
        this.basePath = basePath;
        this.frontMatter = frontMatter;
        this.content = content;
    }

    /**
     * 获取 Skill 根目录。
     *
     * @return 当前执行环境可见的 Skill 根目录
     */
    public String getBasePath() {
        return basePath;
    }

    /** @param basePath 当前执行环境可见的 Skill 根目录 */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    /** @return 从 {@code SKILL.md} 解析出的 front matter */
    public Map<String, Object> getFrontMatter() {
        return frontMatter;
    }

    /** @param frontMatter Skill 元数据 */
    public void setFrontMatter(Map<String, Object> frontMatter) {
        this.frontMatter = frontMatter;
    }

    /** @return 去掉 front matter 后的 Markdown 指令正文 */
    public String getContent() {
        return content;
    }

    /** @param content Markdown 指令正文 */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 获取 Skill 名称。
     *
     * <p>名称来自 front matter 的 {@code name} 字段。加载 Skill 时应确保该字段存在，
     * 否则这里会因为无法生成工具元数据而抛出异常。</p>
     *
     * @return Skill 的唯一调用名称
     */
    public String name() {
        return this.frontMatter.get("name").toString();
    }

    /**
     * 将 front matter 转换为提供给大模型的 XML 摘要。
     *
     * <p>这里只暴露名称、描述等元数据，不包含完整指令正文和脚本内容，用于实现
     * Skills 的渐进式披露：模型先选择 Skill，调用后才获得完整正文。</p>
     *
     * @return {@code <skill>...</skill>} 格式的元数据摘要
     */
    public String toXml() {
        String frontMatterXml = this.frontMatter
            .entrySet()
            .stream()
            .map(e -> String.format("  <%s>%s</%s>", e.getKey(), e.getValue(), e.getKey()))
            .collect(Collectors.joining("\n"));

        return String.format("<skill>\n%s\n</skill>", frontMatterXml);
    }
}
