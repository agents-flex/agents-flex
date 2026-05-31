package com.agentsflex.tool.commons;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.tool.commons.web.*;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

public class WebFetchTool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList("http", "https"));

    private static final int MAX_RETRY = 2;

    private final OkHttpClient httpClient;
    private final FlexmarkHtmlConverter htmlConverter;

    private final int maxContentLength;
    private final int maxCacheSize;

    private final Map<String, CacheEntry> cache;
    private final AdaptiveWebReaderRouter webReaderRouter;

    // ========================= CONSTRUCTOR =========================

    private WebFetchTool(Builder builder) {
        this.httpClient = builder.httpClient;
        this.htmlConverter = FlexmarkHtmlConverter.builder().build();

        this.maxContentLength = builder.maxContentLength;
        this.maxCacheSize = builder.maxCacheSize;

        this.cache = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > maxCacheSize;
            }
        };

        this.webReaderRouter = new AdaptiveWebReaderRouter(builder.providers);
    }

    // ========================= TOOL =========================

    @ToolDef(
        name = "web_fetch",
        description = "Fetch web page content with HTTP + Reader fallback (Jina, etc)."
    )
    public String webFetch(@ToolParam(name = "url", description = "The URL to fetch content from") String url) {
        String validationError = validateUrl(url);
        if (validationError != null) {
            return validationError;
        }

        String cacheKey = buildCacheKey(url);

        String cached = getFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }


        try {
            // 4. 尝试获取内容 (主逻辑)
            String content = fetchAndProcessContent(url);

            // 5. 结果校验与缓存
            if (!StringUtil.hasText(content)) {
                return "Error: Failed to extract meaningful content from the URL.";
            }

            // 可选：只缓存非错误且有一定长度的内容，避免缓存垃圾数据
            putCache(cacheKey, content);

            return content;

        } catch (Exception e) {
            logger.error("Web fetch failed for url: {}", url, e);
            // 注意：这里不再次尝试 fallback，因为 fetchAndProcessContent 内部已经包含了 fallback 逻辑
            // 如果内部所有尝试都失败了，才会抛异常到这里
            return "Error: Unable to fetch content. " + e.getMessage();
        }


    }


    /**
     * URL 校验 helper
     */
    private String validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                return "Error: Unsupported URL protocol. Only http and https are allowed.";
            }

            if (host == null || host.isEmpty()) {
                return "Error: Invalid URL format (missing host).";
            }

            // 可选：禁止内网 IP (简单示例，生产环境需更严谨)
            // if (isInternalIp(host)) return "Error: Access to internal networks is forbidden.";

        } catch (IllegalArgumentException e) {
            return "Error: Malformed URL string.";
        }
        return null; // Valid
    }


    /**
     * 核心提取逻辑：HTTP -> (如果为空或太短) -> Reader Fallback
     */
    private String fetchAndProcessContent(String url) throws Exception {
        // 第一层：尝试直接 HTTP 抓取
        String rawContent = fetchWithHttp(url);

        // 处理并清洗 HTTP 获取的内容
        String processedContent = processContent(rawContent);

        // 如果 HTTP 获取的内容有效且足够长，直接返回
        if (StringUtil.hasText(processedContent) && processedContent.length() >= 200) {
            return processedContent;
        }

        logger.info("HTTP fetch resulted in short/empty content for {}, trying reader fallback...", url);

        // 第二层：Fallback 到 Reader 服务 (Jina, etc.)
        // 注意：fetchWithReaders 内部应该处理网络异常，如果彻底失败应抛出异常或返回 null
        String readerContent = fetchWithReaders(url, this::processContent);

        if (!StringUtil.hasText(readerContent)) {
            throw new RuntimeException("Both HTTP and Reader fallback methods returned empty content.");
        }

        return readerContent;
    }

    /**
     * 统一的内容处理管道：清洗 -> 转换 -> 截断
     * 使用函数式接口方便复用
     */
    private String processContent(String rawHtml) {
        if (!StringUtil.hasText(rawHtml)) {
            return "";
        }
        String cleaned = cleanHtml(rawHtml);
        String converted = htmlConverter.convert(cleaned);
        return truncate(converted);
    }

    // ========================= HTTP =========================

    private String fetchWithHttp(String url) throws Exception {

        Exception last = null;

        for (int i = 0; i <= MAX_RETRY; i++) {
            try {

                HttpResult result = executeGet(url);

                // 成功
                if (result.isSuccess()) {
                    return result.body;
                }

                // 4xx：不可重试，直接失败
                if (!result.isRetryable()) {
                    throw new RuntimeException("HTTP " + result.code + " (non-retryable)");
                }

                // 5xx / 429：可重试
                throw new RuntimeException("HTTP " + result.code + " (retryable)");

            } catch (Exception e) {
                last = e;

                // 最后一轮不 sleep
                if (i < MAX_RETRY) {
                    try {
                        Thread.sleep((long) Math.pow(2, i) * 300);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw last;
    }

    private HttpResult executeGet(String url) throws IOException {

        Request request = OKHttpUtil.defaultRequestBuilder(url).build();

        try (Response response = httpClient.newCall(request).execute()) {

            int code = response.code();
            ResponseBody body = response.body();

            String text = (body != null) ? OKHttpUtil.decodeBody(body) : null;

            return new HttpResult(code, text);
        }
    }


    // ========================= READERS =========================

    private String fetchWithReaders(String url, Function<String, String> transformer) {
        try {
            return webReaderRouter.read(url, transformer);
        } catch (Exception e) {
            logger.warn("Reader fallback failed: {}", url, e);
            return null;
        }
    }

    // ========================= HTML =========================

    private String cleanHtml(String html) {
        if (html == null) return "";
        return html
            .replaceAll("(?s)<script.*?>.*?</script>", "")
            .replaceAll("(?s)<style.*?>.*?</style>", "");
    }

    private String truncate(String content) {
        if (content == null) return "";

        if (content.length() > maxContentLength) {
            return content.substring(0, maxContentLength);
        }

        return content;
    }

    // ========================= CACHE =========================

    private String buildCacheKey(String url) {
        return "web:" + url;
    }

    private synchronized String getFromCache(String key) {

        CacheEntry entry = cache.get(key);

        if (entry == null) return null;

        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }

        return entry.content;
    }

    private synchronized void putCache(String key, String value) {
        cache.put(key, new CacheEntry(value));
    }

    // ========================= CLOSE =========================

    @Override
    public void close() {
        cache.clear();
    }

    // ========================= INNER =========================

    private static class CacheEntry {

        final String content;
        final long timestamp = System.currentTimeMillis();

        CacheEntry(String content) {
            this.content = content;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL.toMillis();
        }
    }


    private static class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean isSuccess() {
            return code >= 200 && code < 300;
        }

        boolean isRetryable() {
            return code >= 500 || code == 429;
        }
    }

    // ========================= BUILDER =========================

    public static class Builder {

        private int maxContentLength = 100_000;
        private int maxCacheSize = 100;
        private OkHttpClient httpClient = OkHttpClientUtil.buildDefaultClient();
        private final List<WebReaderProvider> providers = new ArrayList<>();

        public Builder maxContentLength(int v) {
            this.maxContentLength = v;
            return this;
        }

        public Builder maxCacheSize(int v) {
            this.maxCacheSize = v;
            return this;
        }

        public Builder httpClient(OkHttpClient client) {
            this.httpClient = client;
            return this;
        }


        public Builder addProvider(WebReaderProvider provider) {
            this.providers.add(provider);
            return this;
        }

        public Builder useDefaultProviders() {
            this.providers.add(new HttpReaderProvider(httpClient));
            this.providers.add(new JinaReaderProvider(httpClient));
            return this;
        }

        public WebFetchTool build() {
            if (providers.isEmpty()) {
                throw new IllegalStateException("No WebReaderProvider configured");
            }
            return new WebFetchTool(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
