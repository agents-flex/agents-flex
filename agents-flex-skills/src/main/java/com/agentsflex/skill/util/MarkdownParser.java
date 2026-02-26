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
 * Parser for Markdown documents with optional YAML front matter.
 * <p>
 * This parser extracts YAML front matter (metadata) and content from Markdown documents.
 * Front matter is delimited by triple dashes (---) at the beginning of the document and
 * contains key-value pairs in YAML format.
 * <p>
 * Example Markdown with front matter:
 *
 * <pre>{@code
 * ---
 * title: My Document
 * author: John Doe
 * date: 2024-01-15
 * ---
 *
 * # Heading
 *
 * Document content here.
 * }</pre>
 * <p>
 * The parser supports:
 * <ul>
 * <li>Front matter with key-value pairs separated by colons</li>
 * <li>Values with or without quotes (both single and double quotes are supported)</li>
 * <li>Documents without front matter (entire content is treated as body)</li>
 * <li>Empty or null markdown input</li>
 * </ul>
 *
 * @author Christian Tzolov
 */
public class MarkdownParser {

	/**
	 * Map containing the parsed front matter key-value pairs.
	 */
	private Map<String, Object> frontMatter;

	/**
	 * The content of the markdown document (everything after the front matter).
	 */
	private String content;

	/**
	 * Constructs a new MarkdownParser and parses the provided markdown content. Parses
	 * the markdown content to extract front matter and body content.
	 * <p>
	 * Front matter must start with "---" at the beginning of the document and end with
	 * another "---". Everything between these delimiters is parsed as front matter.
	 * Everything after the closing delimiter is treated as content.
	 * @param markdown the markdown string to parse, may contain front matter delimited by
	 * triple dashes (---). Can be null or empty.
	 */
	public MarkdownParser(String markdown) {

		frontMatter = new HashMap<>();
		content = "";

		if (markdown == null || markdown.isEmpty()) {
			return;
		}

		// Check if document starts with front-matter delimiter (---)
		if (markdown.startsWith("---")) {
			// Find the closing delimiter
			int endIndex = markdown.indexOf("---", 3);

			if (endIndex != -1) {
				// Extract front-matter section
				String frontMatterSection = markdown.substring(3, endIndex).trim();
				parseFrontMatter(frontMatterSection);

				// Extract remaining content (skip the closing --- and any following
				// newlines)
				content = markdown.substring(endIndex + 3).trim();
			}
			else {
				// No closing delimiter found, treat entire document as content
				content = markdown;
			}
		}
		else {
			// No front-matter, entire document is content
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

			// Split on first colon
			int colonIndex = line.indexOf(':');
			if (colonIndex > 0) {
				String key = line.substring(0, colonIndex).trim();
				String value = line.substring(colonIndex + 1).trim();

				// Removes surrounding quotes from a value string if present.
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
	 * Returns a copy of the parsed front matter as a map.
	 * <p>
	 * The returned map contains all key-value pairs extracted from the front matter
	 * section. If no front matter was present or the input was null/empty, returns an
	 * empty map.
	 * @return a new map containing the front matter key-value pairs
	 */
	public Map<String, Object> getFrontMatter() {
		return new HashMap<>(frontMatter);
	}

	/**
	 * Returns the content portion of the markdown document.
	 * <p>
	 * This is everything after the closing front matter delimiter (---), with leading and
	 * trailing whitespace trimmed. If no front matter was present, returns the entire
	 * document. If the input was null or empty, returns an empty string.
	 * @return the markdown content as a string
	 */
	public String getContent() {
		return content;
	}
}
