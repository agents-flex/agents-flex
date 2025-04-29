package com.agentsflex.llm.qwen;

import java.util.List;

import com.alibaba.fastjson2.annotation.JSONField;

import com.agentsflex.core.llm.ChatOptions;

/**
 * <link href="https://help.aliyun.com/zh/model-studio/use-qwen-by-calling-api">通义千问API参考</link>
 *
 * @author liutf
 */
public class QwenChatOptions extends ChatOptions {
    /**
     * 输出数据的模态，仅支持 Qwen-Omni 模型指定。（可选）
     * 默认值为["text"]
     * 可选值：
     * ["text"]：输出文本。
     */
    private List<String> modalities;

    /**
     * 控制模型生成文本时的内容重复度。（可选）
     * 取值范围：[-2.0, 2.0]。正数会减少重复度，负数会增加重复度。
     * <pre>
     * 适用场景：
     *      较高的presence_penalty适用于要求多样性、趣味性或创造性的场景，如创意写作或头脑风暴。
     *      较低的presence_penalty适用于要求一致性或专业术语的场景，如技术文档或其他正式文档。
     * </pre>
     * 不建议修改QVQ模型的默认presence_penalty值。
     */
    private Float presencePenalty;

    /**
     * 返回内容的格式。（可选） 默认值为{"type": "text"}
     * 可选值：{"type": "text"}或{"type": "json_object"}。
     * 设置为{"type": "json_object"}时会输出标准格式的JSON字符串。
     * 支持的模型和使用方法请参见结构化输出: https://help.aliyun.com/zh/model-studio/json-mode
     */
    private ResponseFormat responseFormat;

    /**
     * 生成响应的个数，取值范围是1-4。
     * 对于需要生成多个响应的场景（如创意写作、广告文案等），可以设置较大的 n 值。
     * <pre>
     *     当前仅支持 qwen-plus 模型，且在传入 tools 参数时固定为1。
     *     设置较大的 n 值不会增加输入 Token 消耗，会增加输出 Token 的消耗。
     * </pre>
     */
    private Integer n;

    /**
     * 是否开启并行工具调用。
     * 参数为true时开启，为false时不开启。
     * 并行工具调用请参见：https://help.aliyun.com/zh/model-studio/qwen-function-calling#cb6b5c484bt4x
     */
    private Boolean parallelToolCalls;

    /**
     * 当您使用翻译模型时需要配置的翻译参数。
     */
    private TranslationOptions translationOptions;

    /**
     * 用于控制模型在生成文本时是否使用互联网搜索结果进行参考。
     * 取值如下：
     * True：启用互联网搜索，模型会将搜索结果作为文本生成过程中的参考信息，但模型会基于其内部逻辑判断是否使用互联网搜索结果。
     * False（默认）：关闭互联网搜索。
     * 启用互联网搜索功能可能会增加 Token 的消耗。
     * 当前支持 qwen-max、qwen-plus、qwen-turbo
     */
    private Boolean enableSearch;

    /**
     * 是否开启思考模式，适用于 Qwen3 模型。
     * Qwen3 商业版模型默认值为 False，Qwen3 开源版模型默认值为 True。
     */
    private Boolean enableThinking;

    /**
     * 思考模式预算，适用于 Qwen3 模型。
     * 思考过程的最大长度，只在 enable_thinking 为true时生效。适用于 Qwen3 的商业版与开源版模型。
     * 详情请参见限制思考长度：https://help.aliyun.com/zh/model-studio/deep-thinking#e7c0002fe4meu
     */
    private Integer thinkingBudget;

    /**
     * 联网搜索的策略。
     * 仅当enable_search为true时生效。
     */
    private SearchOptions searchOptions;

    public static class TranslationOptions {
        /**
         * （必选）
         * 源语言的英文全称
         * 您可以将source_lang设置为"auto"，模型会自动判断输入文本属于哪种语言。
         * 支持的语言: https://help.aliyun.com/zh/model-studio/user-guide/machine-translation#038d2865bbydc
         */
        @JSONField(name = "source_lang")
        private String sourceLang;

        /**
         * （必选）
         * 目标语言的英文全称，
         * 支持的语言: https://help.aliyun.com/zh/model-studio/user-guide/machine-translation#038d2865bbydc
         */
        @JSONField(name = "target_lang")
        private String targetLang;

        /**
         * 在使用术语干预翻译功能时需要设置的术语数组。
         * https://help.aliyun.com/zh/model-studio/user-guide/machine-translation#2bf54a5ab5voe
         */
        @JSONField(name = "terms")
        private List<TranslationOptionsExt> terms;

        /**
         * 在使用翻译记忆功能时需要设置的翻译记忆数组。
         * https://help.aliyun.com/zh/model-studio/user-guide/machine-translation#17e15234e7gfp
         */
        @JSONField(name = "tm_list")
        private List<TranslationOptionsExt> tmList;

        /**
         * （可选）
         * 在使用领域提示功能时需要设置的领域提示语句。
         * 领域提示语句暂时只支持英文。
         * https://help.aliyun.com/zh/model-studio/user-guide/machine-translation#4af23a31db7lf
         */
        @JSONField(name = "domains")
        private String domains;

        public String getSourceLang() {
            return sourceLang;
        }

        public TranslationOptions setSourceLang(String sourceLang) {
            this.sourceLang = sourceLang;
            return this;
        }

        public String getTargetLang() {
            return targetLang;
        }

        public TranslationOptions setTargetLang(String targetLang) {
            this.targetLang = targetLang;
            return this;
        }

        public List<TranslationOptionsExt> getTerms() {
            return terms;
        }

        public TranslationOptions setTerms(List<TranslationOptionsExt> terms) {
            this.terms = terms;
            return this;
        }

        public List<TranslationOptionsExt> getTmList() {
            return tmList;
        }

        public TranslationOptions setTmList(List<TranslationOptionsExt> tmList) {
            this.tmList = tmList;
            return this;
        }

        public String getDomains() {
            return domains;
        }

        public TranslationOptions setDomains(String domains) {
            this.domains = domains;
            return this;
        }
    }

    public static class TranslationOptionsExt {
        /**
         * 源语言的术语/源语言的语句
         */
        private String source;
        /**
         * 目标语言的术语/目标语言的语句
         */
        private String target;

        public String getSource() {
            return source;
        }

        public TranslationOptionsExt setSource(String source) {
            this.source = source;
            return this;
        }

        public String getTarget() {
            return target;
        }

        public TranslationOptionsExt setTarget(String target) {
            this.target = target;
            return this;
        }
    }

    public static class ResponseFormat {
        /**
         * 返回内容的格式。
         * 可选值："text" 或 "json_object"
         * 默认值为 "text"
         */
        private String type;

        public String getType() {
            return type;
        }

        public ResponseFormat setType(String type) {
            this.type = type;
            return this;
        }
    }

    public static class SearchOptions {
        /**
         * 是否强制开启搜索。（可选）默认值为false
         * 参数值：true=强制开启；false=不强制开启。
         */
        @JSONField(name = "forced_search")
        private Boolean forcedSearch;

        /**
         * 搜索互联网信息的数量，（可选）默认值为"standard"
         * 参数值：
         * standard：在请求时搜索5条互联网信息；
         * pro：在请求时搜索10条互联网信息。
         */
        @JSONField(name = "search_strategy")
        private String searchStrategy;

        public Boolean getForcedSearch() {
            return forcedSearch;
        }

        public SearchOptions setForcedSearch(Boolean forcedSearch) {
            this.forcedSearch = forcedSearch;
            return this;
        }

        public String getSearchStrategy() {
            return searchStrategy;
        }

        public SearchOptions setSearchStrategy(String searchStrategy) {
            this.searchStrategy = searchStrategy;
            return this;
        }
    }

    public List<String> getModalities() {
        return modalities;
    }

    public QwenChatOptions setModalities(List<String> modalities) {
        this.modalities = modalities;
        return this;
    }

    public Float getPresencePenalty() {
        return presencePenalty;
    }

    public QwenChatOptions setPresencePenalty(Float presencePenalty) {
        this.presencePenalty = presencePenalty;
        return this;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public QwenChatOptions setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
        return this;
    }

    public Integer getN() {
        return n;
    }

    public QwenChatOptions setN(Integer n) {
        this.n = n;
        return this;
    }

    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    public QwenChatOptions setParallelToolCalls(Boolean parallelToolCalls) {
        this.parallelToolCalls = parallelToolCalls;
        return this;
    }

    public TranslationOptions getTranslationOptions() {
        return translationOptions;
    }

    public QwenChatOptions setTranslationOptions(TranslationOptions translationOptions) {
        this.translationOptions = translationOptions;
        return this;
    }

    public Boolean getEnableSearch() {
        return enableSearch;
    }

    public QwenChatOptions setEnableSearch(Boolean enableSearch) {
        this.enableSearch = enableSearch;
        return this;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    public void setThinkingBudget(Integer thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
    }

    public SearchOptions getSearchOptions() {
        return searchOptions;
    }

    public QwenChatOptions setSearchOptions(SearchOptions searchOptions) {
        this.searchOptions = searchOptions;
        return this;
    }
}
