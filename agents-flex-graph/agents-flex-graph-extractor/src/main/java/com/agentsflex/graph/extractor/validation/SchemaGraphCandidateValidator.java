package com.agentsflex.graph.extractor.validation;

import com.agentsflex.graph.extractor.GraphCandidateResult;
import com.agentsflex.graph.extractor.GraphExtractionOptions;
import com.agentsflex.graph.extractor.GraphExtractionRequest;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.schema.GraphSchema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验节点类型、关系端点、属性、证据、置信度和断言类型的默认实现。
 *
 * <p>校验器不会修改候选内容，而是构造一个只包含合法候选的新批次。被拒绝的实体仍保留在
 * {@code GraphExtractionResult.getAllEntities()} 等原始候选集合中，并通过结构化问题解释原因，
 * 便于开发者实现人工审核、质量统计和重试。</p>
 */
public final class SchemaGraphCandidateValidator implements GraphCandidateValidator {
    /**
     * 按请求 Schema 和质量选项筛选可接受候选项。
     *
     * <p>先校验实体，再校验关系。关系只能引用本轮已经通过校验的实体，因此坏实体不会通过
     * 关系间接进入后续 GraphMutation。</p>
     *
     * @param candidates 解析器生成的候选结果
     * @param request 当前 Chunk 的抽取请求
     * @return 合法候选及解析、校验阶段的完整问题列表
     */
    @Override
    public GraphCandidateValidationResult validate(GraphCandidateResult candidates, GraphExtractionRequest request) {
        if (candidates == null || request == null) throw new IllegalArgumentException("candidates and request must not be null");
        Map<String, GraphSchema.NodeType> nodeTypes = nodeTypes(request.getSchema());
        Map<String, GraphSchema.EdgeType> edgeTypes = edgeTypes(request.getSchema());
        List<GraphExtractionIssue> issues = new ArrayList<>(candidates.getIssues());
        List<GraphEntityCandidate> entities = new ArrayList<>();
        Map<String, GraphEntityCandidate> acceptedByKey = new HashMap<>();
        Set<String> seenKeys = new HashSet<>();
        for (GraphEntityCandidate entity : candidates.getEntities()) {
            String problem = entityProblem(entity, nodeTypes.get(entity.getType()), request, seenKeys);
            if (problem == null) {
                entities.add(entity);
                acceptedByKey.put(entity.getCandidateKey(), entity);
            } else {
                issues.add(error("INVALID_ENTITY", entity.getCandidateKey(), problem));
            }
        }
        List<GraphRelationCandidate> relations = new ArrayList<>();
        for (GraphRelationCandidate relation : candidates.getRelations()) {
            String problem = relationProblem(relation, edgeTypes.get(relation.getType()), acceptedByKey,
                request);
            if (problem == null) relations.add(relation);
            else
                issues.add(error("INVALID_RELATION", relation.getSourceCandidateKey() + "->" + relation.getTargetCandidateKey(), problem));
        }
        return new GraphCandidateValidationResult(new GraphCandidateResult(entities, relations, issues, candidates.getRawResponse()));
    }

    /**
     * 返回实体的第一个校验问题；返回 {@code null} 表示实体合法。
     */
    private static String entityProblem(GraphEntityCandidate entity, GraphSchema.NodeType type,
                                        GraphExtractionRequest request, Set<String> seenKeys) {
        String expectedPrefix = request.getChunkId() + "::";
        if (!entity.getCandidateKey().startsWith(expectedPrefix)
            || entity.getCandidateKey().length() == expectedPrefix.length()) {
            return "candidate key is outside the current chunk scope";
        }
        if (!seenKeys.add(entity.getCandidateKey())) return "duplicate candidate key";
        if (type == null) return "unknown node type: " + entity.getType();
        String quality = qualityProblem(entity.getConfidence(), entity.getEvidence(), request);
        if (quality != null) return quality;
        return propertyProblem(entity.getProperties(), type.getProperties());
    }

    /**
     * 返回关系的第一个校验问题；同时验证端点存在性、方向、断言策略与属性。
     */
    private static String relationProblem(GraphRelationCandidate relation, GraphSchema.EdgeType type,
                                          Map<String, GraphEntityCandidate> entities, GraphExtractionRequest request) {
        GraphExtractionOptions options = request.getOptions();
        if (type == null) return "unknown edge type: " + relation.getType();
        GraphEntityCandidate source = entities.get(relation.getSourceCandidateKey());
        GraphEntityCandidate target = entities.get(relation.getTargetCandidateKey());
        if (source == null || target == null) return "relation endpoint does not reference an accepted entity";
        if (type.getSourceLabel() != null && !type.getSourceLabel().equals(source.getType()))
            return "source type does not match Schema";
        if (type.getTargetLabel() != null && !type.getTargetLabel().equals(target.getType()))
            return "target type does not match Schema";
        if (relation.getAssertionType() == GraphAssertionType.INFERRED && !options.isIncludeInferredRelations())
            return "inferred relations are disabled";
        if (relation.getAssertionType() == GraphAssertionType.OPINION && !options.isIncludeOpinionRelations())
            return "opinion relations are disabled";
        String quality = qualityProblem(relation.getConfidence(), relation.getEvidence(), request);
        return quality == null ? propertyProblem(relation.getProperties(), type.getProperties()) : quality;
    }

    /**
     * 校验候选置信度与证据定位。
     *
     * <p>未知偏移必须同时为 {@code -1/-1}。已知偏移采用 Java 字符串下标语义，结束位置为
     * exclusive；对应子串必须与 quote 完全一致。即使没有偏移，quote 也必须是当前 Chunk 的
     * 连续原文，不能引用仅用于指代消解的前文。</p>
     */
    private static String qualityProblem(double confidence, GraphEvidence evidence, GraphExtractionRequest request) {
        GraphExtractionOptions options = request.getOptions();
        if (confidence < options.getMinConfidence()) return "confidence is below minimum threshold";
        String quote = evidence == null ? "" : evidence.getQuote();
        if (options.isRequireEvidence() && quote.trim().isEmpty()) return "evidence is required";
        if (evidence != null && !request.getChunkId().equals(evidence.getChunkId())) {
            return "evidence chunk id does not match the current request";
        }
        if (evidence != null && !request.getDocumentId().isEmpty()
            && !request.getDocumentId().equals(evidence.getDocumentId())) {
            return "evidence document id does not match the current request";
        }
        if (!quote.isEmpty() && !request.getText().contains(quote)) {
            return "evidence is not an exact excerpt of the current text";
        }
        if (evidence == null) return null;
        int start = evidence.getStartOffset();
        int end = evidence.getEndOffset();
        if (start == -1 && end == -1) return null;
        if (start < 0 || end < 0 || start > end || end > request.getText().length()) {
            return "evidence offsets are outside the current text";
        }
        if (!request.getText().substring(start, end).equals(quote)) {
            return "evidence offsets do not match the evidence quote";
        }
        return null;
    }

    /**
     * 校验候选属性白名单、可移植类型、枚举限制和必填约束。
     *
     * @param values 待校验的完整属性映射
     * @param definitions Schema 属性定义
     * @return 首个问题说明；合法时返回 null，供抽取和人工修改共用相同约束
     */
    public static String propertyProblem(Map<String, Object> values, List<GraphSchema.Property> definitions) {
        Map<String, GraphSchema.Property> properties = new HashMap<>();
        for (GraphSchema.Property property : definitions) properties.put(property.getName(), property);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            GraphSchema.Property property = properties.get(entry.getKey());
            if (property == null) return "unknown property: " + entry.getKey();
            if (!matches(entry.getValue(), property.getType()))
                return "invalid value type for property: " + entry.getKey();
            if (!property.getMetadata().getEnumValues().isEmpty() && entry.getValue() != null
                && !property.getMetadata().getEnumValues().contains(String.valueOf(entry.getValue()))) {
                return "value is not in the allowed enum for property: " + entry.getKey();
            }
        }
        for (GraphSchema.Property property : definitions) {
            if (property.isRequired() && (!values.containsKey(property.getName()) || values.get(property.getName()) == null)) {
                return "missing required property: " + property.getName();
            }
        }
        return null;
    }

    /**
     * 判断 Java 值是否符合 GraphSchema 的可移植属性类型。
     *
     * <p>模型 JSON 中的日期通常表现为字符串，因此 DATE 和 DATETIME 同时接受 Java 时间对象
     * 与严格 ISO-8601 字符串；不能解析的任意字符串不会再被误判为合法日期。</p>
     */
    private static boolean matches(Object value, GraphSchema.PropertyType type) {
        if (value == null) return true;
        switch (type) {
            case STRING:
                return value instanceof String;
            case BOOLEAN:
                return value instanceof Boolean;
            case INT64:
                return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case DOUBLE:
                return finiteNumber(value);
            case DATE:
                return value instanceof LocalDate || parseDate(value);
            case DATETIME:
                return value instanceof LocalDateTime || parseDateTime(value);
            default:
                return false;
        }
    }

    /**
     * DOUBLE 接受任意 Number 子类，但拒绝无法安全写入多数图数据库的 NaN 和无穷值。
     */
    private static boolean finiteNumber(Object value) {
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return !Double.isNaN(number) && !Double.isInfinite(number);
    }

    /**
     * 尝试按 ISO-8601 日期格式解析字符串值。
     */
    private static boolean parseDate(Object value) {
        if (!(value instanceof String)) return false;
        try {
            LocalDate.parse((String) value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /**
     * 尝试按 ISO-8601 本地日期时间格式解析字符串值。
     */
    private static boolean parseDateTime(Object value) {
        if (!(value instanceof String)) return false;
        try {
            LocalDateTime.parse((String) value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /**
     * 按节点标签建立 Schema 快速查找表。
     */
    private static Map<String, GraphSchema.NodeType> nodeTypes(GraphSchema schema) {
        Map<String, GraphSchema.NodeType> result = new HashMap<>();
        for (GraphSchema.NodeType type : schema.getNodeTypes()) result.put(type.getLabel(), type);
        return result;
    }

    /**
     * 按关系类型建立 Schema 快速查找表。
     */
    private static Map<String, GraphSchema.EdgeType> edgeTypes(GraphSchema schema) {
        Map<String, GraphSchema.EdgeType> result = new HashMap<>();
        for (GraphSchema.EdgeType type : schema.getEdgeTypes()) result.put(type.getType(), type);
        return result;
    }

    /**
     * 创建 ERROR 级别的候选校验问题。
     */
    private static GraphExtractionIssue error(String code, String key, String message) {
        return new GraphExtractionIssue(code, GraphExtractionIssue.Severity.ERROR, key, message);
    }
}
