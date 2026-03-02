package com.agentsflex.skill;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a SKILL.md file with its location and parsed content.
 */
public class Skill {

    private String basePath;
    private Map<String, Object> frontMatter;
    private String content;

    public Skill() {
    }

    public Skill(String basePath, Map<String, Object> frontMatter, String content) {
        this.basePath = basePath;
        this.frontMatter = frontMatter;
        this.content = content;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public Map<String, Object> getFrontMatter() {
        return frontMatter;
    }

    public void setFrontMatter(Map<String, Object> frontMatter) {
        this.frontMatter = frontMatter;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String name() {
        return this.frontMatter.get("name").toString();
    }

    public String toXml() {
        String frontMatterXml = this.frontMatter
            .entrySet()
            .stream()
            .map(e -> String.format("  <%s>%s</%s>", e.getKey(), e.getValue(), e.getKey()))
            .collect(Collectors.joining("\n"));

        return String.format("<skill>\n%s\n</skill>", frontMatterXml);
    }
}
