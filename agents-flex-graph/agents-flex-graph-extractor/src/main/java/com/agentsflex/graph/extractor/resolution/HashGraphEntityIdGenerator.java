package com.agentsflex.graph.extractor.resolution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

/**
 * 根据实体类型和规范化名称生成确定性 SHA-256 摘要 ID。
 *
 * <p>相同输入始终得到相同 ID，便于同一批次重复执行。它不查询已有图谱，因此无法判断两个
 * 批次中的同名实体是否确实是同一对象；长期知识入图应考虑业务实体注册策略。</p>
 */
public final class HashGraphEntityIdGenerator implements GraphEntityIdGenerator {
    /**
     * 生成“类型小写:24 位十六进制摘要”形式的节点 ID。
     *
     * @param type          Schema 节点类型
     * @param canonicalName 实体归一后的主名称
     * @return 可重复生成的非空节点 ID
     */
    @Override
    public String generate(String type, String canonicalName) {
        if (type == null || canonicalName == null)
            throw new IllegalArgumentException("type and canonicalName must not be null");
        String source = normalize(type) + "\n" + normalize(canonicalName);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder(type.toLowerCase(Locale.ROOT)).append(':');
            for (int i = 0; i < 12; i++) id.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            return id.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * 统一 Unicode 兼容形式、大小写和连续空白，确保等价文本得到相同匹配值。
     */
    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }
}
