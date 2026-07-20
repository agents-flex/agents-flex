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
package com.agentsflex.tool.webfetch.web;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OKHttpUtil {

    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static Request.Builder defaultRequestBuilder(String url) {
        return new Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get();
    }

    public static String decodeBody(ResponseBody body) throws IOException {
        byte[] bytes = body.bytes();
        MediaType contentType = body.contentType();

        Charset initialCharset = null;

        // =========================
        // 1. HTTP Header charset
        // =========================
        if (contentType != null) {
            initialCharset = contentType.charset();
        }

        // =========================
        // 2. BOM detect (UTF-8/UTF-16)
        // =========================
        if (initialCharset == null) {
            if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
                initialCharset = StandardCharsets.UTF_8;
            }
        }

        // =========================
        // 3. HTML meta charset detect
        // =========================
        if (initialCharset == null) {

            String head = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9\\-]+)"
                    , Pattern.CASE_INSENSITIVE)
                .matcher(head);

            if (m.find()) {
                try {
                    initialCharset = Charset.forName(m.group(1));
                } catch (Exception ignored) {
                }
            }
        }

        if (initialCharset == null) {
            initialCharset = StandardCharsets.UTF_8;
        }

        // =========================
        // 4. decode first try
        // =========================
        String text = new String(bytes, initialCharset);

        if (!looksBroken(text)) {
            return text;
        }

        // =========================
        // 5. auto-repair charset retry
        // =========================
        Charset[] fallbackCharsets = new Charset[]{
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            Charset.forName("GB2312"),
            StandardCharsets.ISO_8859_1
        };

        String bestText = text;
        int bestScore = scoreText(text);

        for (Charset cs : fallbackCharsets) {

            if (cs.equals(initialCharset)) continue;

            String tryText = new String(bytes, cs);
            int score = scoreText(tryText);

            if (score > bestScore) {
                bestScore = score;
                bestText = tryText;
            }
        }

        return bestText;
    }


    private static boolean looksBroken(String text) {
        if (text == null || text.isEmpty()) return true;

        long bad = text.chars().filter(c -> c == 0xFFFD).count();
        if (bad > text.length() * 0.01) return true;
        if (text.contains("���")) return true;

        return false;
    }

    private static int scoreText(String text) {
        if (text == null || text.isEmpty()) return 0;

        int score = 0;

        // 中文比例（核心指标）
        long chinese = text.chars().filter(
                c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
            .count();

        score += (int) (chinese * 2);

        // penalty: replacement chars
        long bad = text.chars().filter(c -> c == 0xFFFD).count();
        score -= bad * 10;

        // penalty: garbage
        if (text.contains("���")) score -= 100;

        return score;
    }
}
