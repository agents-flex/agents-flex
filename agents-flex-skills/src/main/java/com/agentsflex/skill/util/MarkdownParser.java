/*
* Copyright 2025 - 2025 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.agentsflex.skill.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 解析带有可选 YAML front matter 的 Markdown 文档。
 *
 * <p>front matter 必须位于文件开头，并由两行 {@code ---} 包围。本解析器提取其中的
 * 简单键值对作为元数据，同时保留分隔符之后的 Markdown 正文。</p>
 *
 * <p>示例：</p>
 *
 * <pre>{@code
 * ---
 * name: pdf
 * description: Create PDF documents
 * ---
 *
 * # Heading
 *
 * Skill instructions here.
 * }</pre>
 *
 * <p>支持的语法：</p>
 * <ul>
 * <li>使用第一个冒号分隔键和值的单行键值对；</li>
 * <li>无引号、单引号或双引号包裹的值；</li>
 * <li>不含 front matter 的普通 Markdown；</li>
 * <li>{@code null} 或空输入。</li>
 * </ul>
 *
 * <p>这不是完整 YAML 解析器，不支持嵌套对象、数组、块文本等高级 YAML 语法。</p>
 *
 * @author Christian Tzolov
 */
public class MarkdownParser {

	/** 已解析的 front matter 键值对。 */
	private Map<String, Object> frontMatter;

	/** front matter 结束后的 Markdown 正文。 */
	private String content;

	/**
	 * 创建解析器并立即解析输入内容。
	 *
	 * @param markdown 待解析 Markdown；可以为 {@code null} 或空字符串
	 */
	public MarkdownParser(String markdown) {

		frontMatter = new HashMap<>();
		content = "";

		if (markdown == null || markdown.isEmpty()) {
			return;
		}

		// 只有文件开头的 --- 才会被识别为 front matter 起始分隔符。
		if (markdown.startsWith("---")) {
			// 查找结束分隔符。
			int endIndex = markdown.indexOf("---", 3);

			if (endIndex != -1) {
				// 解析两个分隔符之间的元数据。
				String frontMatterSection = markdown.substring(3, endIndex).trim();
				parseFrontMatter(frontMatterSection);

				// 跳过结束分隔符，并去掉正文首尾空白。
				content = markdown.substring(endIndex + 3).trim();
			}
			else {
				// 缺少结束分隔符时不猜测元数据，整份文档按正文处理。
				content = markdown;
			}
		}
		else {
			// 普通 Markdown 全部作为正文。
			content = markdown;
		}

	}

	private void parseFrontMatter(String frontMatterSection) {
		String[] lines = frontMatterSection.split("\n");

		for (String line : lines) {
			line = line.trim();

			if (line.isEmpty()) {
				continue;
			}

			// 只按第一个冒号切分，值本身可以继续包含冒号。
			int colonIndex = line.indexOf(':');
			if (colonIndex > 0) {
				String key = line.substring(0, colonIndex).trim();
				String value = line.substring(colonIndex + 1).trim();

				// 去掉成对的单引号或双引号。
				value = removeQuotes(value);

				frontMatter.put(key, value);
			}
		}
	}

	private String removeQuotes(String value) {
		if (value.length() >= 2) {
			if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

	/**
	 * 获取 front matter 的副本，调用方修改返回 Map 不会影响解析器内部状态。
	 *
	 * @return front matter 键值对；未提供元数据时为空 Map
	 */
	public Map<String, Object> getFrontMatter() {
		return new HashMap<>(frontMatter);
	}

	/**
	 * 获取去掉 front matter 后的 Markdown 正文。
	 *
	 * @return 正文；输入为空时返回空字符串
	 */
	public String getContent() {
		return content;
	}
}
