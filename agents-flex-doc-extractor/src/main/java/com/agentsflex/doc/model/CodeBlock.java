package com.agentsflex.doc.model;

/**
 * 保留空白和换行的代码块。
 */
public final class CodeBlock implements DocumentBlock {
    /**
     * 代码文本。
     */
    private final String code;
    /**
     * 可选语言标识。
     */
    private final String language;

    /**
     * 创建代码块。
     *
     * @param code     代码文本
     * @param language 可选语言标识
     */
    public CodeBlock(String code, String language) {
        this.code = code == null ? "" : code;
        this.language = language;
    }

    /**
     * @return 非 {@code null} 的代码文本
     */
    public String getCode() {
        return code;
    }

    /**
     * @return 语言标识，可为 {@code null}
     */
    public String getLanguage() {
        return language;
    }
}
