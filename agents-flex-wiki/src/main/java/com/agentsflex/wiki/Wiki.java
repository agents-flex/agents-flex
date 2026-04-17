package com.agentsflex.wiki;

import java.util.Map;
import java.util.stream.Collectors;

public class Wiki {

    private String path;

    private String title;
    private String description;
    private String content;

    private Map<String, Object> frontMatter;

    public Wiki() {
    }

    public Wiki(String path, String title, String description) {
        this.path = path;
        this.title = title;
        this.description = description;
    }

    public Wiki(String path, String title, String description, Map<String, Object> frontMatter) {
        this.path = path;
        this.title = title;
        this.description = description;
        this.frontMatter = frontMatter;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getFrontMatter() {
        return frontMatter;
    }

    public void setFrontMatter(Map<String, Object> frontMatter) {
        this.frontMatter = frontMatter;
    }

    public String toXml() {
        String frontMatterXml = this.frontMatter == null ? "" : this.frontMatter
            .entrySet()
            .stream()
            .map(e -> String.format("  <%s>%s</%s>", e.getKey(), e.getValue(), e.getKey()))
            .collect(Collectors.joining("\n"));

        return String.format("<wiki>\n<path>%s</path>\n<title>%s</title>\n<description>%s</description>\n%s\n</wiki>", path, title, description, frontMatterXml);
    }

    public String toMarkdown() {
        return "---\n" +
            "path: " + this.path + "\n" +
            "title: " + this.title + "\n" +
            "description: " + this.description + "\n" +
            (this.frontMatter == null ? "" : this.frontMatter.entrySet().stream().map(e -> String.format("%s: %s", e.getKey(), e.getValue())).collect(Collectors.joining("\n")) )+
            "\n---\n" +
            this.content;
    }
}
