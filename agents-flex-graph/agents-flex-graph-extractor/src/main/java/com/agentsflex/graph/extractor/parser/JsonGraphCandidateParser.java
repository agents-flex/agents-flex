package com.agentsflex.graph.extractor.parser;

import com.agentsflex.graph.extractor.GraphCandidateResult;
import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.GraphExtractionRequest;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用 Fastjson2 解析默认提示词约定的 JSON 响应。
 *
 * <p>解析器只负责把模型协议转换为候选对象，不负责判断候选是否符合业务 Schema。
 * JSON 根对象损坏时会终止当前 Chunk；单个实体或关系损坏时则记录结构化问题并继续解析，
 * 避免一个局部格式错误丢弃同一响应中的全部合法候选。</p>
 */
public final class JsonGraphCandidateParser implements GraphCandidateParser {
    /**
     * 解析实体和关系，并给模型局部 mentionId 添加 chunkId 作用域。
     *
     * @param response 模型返回的 JSON 文本，也可以包含 Markdown 围栏或前后说明
     * @param request  当前 Chunk 的抽取请求，用于补充来源信息和解析上限
     * @return 包含合法候选、逐项解析问题和原始响应的不可变候选结果
     * @throws GraphExtractionException 响应为空、找不到 JSON 根对象或根对象无法解析时抛出
     */
    @Override
    public GraphCandidateResult parse(String response, GraphExtractionRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (response == null || response.trim().isEmpty())
            throw new GraphExtractionException("Graph extraction response is empty");
        if (response.length() > request.getOptions().getMaxResponseCharacters()) {
            throw new GraphExtractionException("Graph extraction response exceeds the configured character limit");
        }
        try {
            JSONObject root = protocolRoot(response);
            List<GraphExtractionIssue> issues = new ArrayList<>();
            List<GraphEntityCandidate> entities = parseEntities(root.getJSONArray("entities"), request, issues);
            List<GraphRelationCandidate> relations = parseRelations(root.getJSONArray("relations"), request, issues);
            return new GraphCandidateResult(entities, relations, issues, response);
        } catch (GraphExtractionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GraphExtractionException("Unable to parse graph extraction response", exception);
        }
    }

    /**
     * 在原始响应中定位满足默认协议的 JSON 根对象。
     *
     * <p>扫描过程理解字符串转义和花括号嵌套，因此 evidence 中的花括号不会截断对象；同时会
     * 逐个尝试说明文本中的对象，避免 JSON 前后额外出现花括号时把整个响应错误拼接为一个对象。
     * 根对象必须同时包含数组类型的 entities 和 relations，防止错误协议被静默当成空结果。</p>
     */
    private static JSONObject protocolRoot(String response) {
        // 允许尝试多个说明文本对象，但限制累计扫描字符数，避免大量未闭合花括号造成平方级退化。
        long remainingScanCharacters = (long) response.length() * 4L;
        for (int start = response.indexOf('{'); start >= 0; start = response.indexOf('{', start + 1)) {
            if (remainingScanCharacters <= 0L) break;
            int maxScanCharacters = (int) Math.min(remainingScanCharacters, response.length() - start);
            int end = objectEnd(response, start, maxScanCharacters);
            remainingScanCharacters -= end < 0 ? maxScanCharacters : end - start + 1L;
            if (end < 0) continue;
            try {
                JSONObject root = JSON.parseObject(response.substring(start, end + 1));
                if (root != null && root.get("entities") instanceof JSONArray
                    && root.get("relations") instanceof JSONArray) {
                    return root;
                }
            } catch (RuntimeException ignored) {
                // 当前花括号片段可能只是说明文本，继续寻找后续协议对象。
            }
        }
        throw new GraphExtractionException(
            "Graph extraction response must contain a JSON object with entities and relations arrays");
    }

    /**
     * 返回指定左花括号对应的右花括号位置；在扫描预算内未闭合时返回 -1。
     *
     * @param value             待扫描响应
     * @param start             当前候选对象的左花括号位置
     * @param maxScanCharacters 本次尝试最多检查的字符数
     */
    private static int objectEnd(String value, int start, int maxScanCharacters) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int limit = (int) Math.min((long) value.length(), (long) start + maxScanCharacters);
        for (int i = start; i < limit; i++) {
            char current = value.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') inString = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /**
     * 逐项解析实体候选。
     *
     * <p>每个实体单独建立异常边界：格式错误的实体生成 {@code MALFORMED_ENTITY}，后续实体仍会
     * 继续解析。这样既保留可用结果，也让调用方能够定位具体数组下标。</p>
     */
    private static List<GraphEntityCandidate> parseEntities(JSONArray array, GraphExtractionRequest request,
                                                            List<GraphExtractionIssue> issues) {
        if (array == null) return Collections.emptyList();
        int limit = request.getOptions().getMaxEntitiesPerChunk();
        if (array.size() > limit)
            issues.add(new GraphExtractionIssue("ENTITY_LIMIT", GraphExtractionIssue.Severity.WARNING,
                request.getChunkId(), "Entity candidates were truncated to " + limit));
        List<GraphEntityCandidate> result = new ArrayList<>();
        for (int i = 0; i < Math.min(array.size(), limit); i++) {
            try {
                JSONObject value = array.getJSONObject(i);
                String localId = required(value, "mentionId");
                String name = required(value, "name");
                String type = required(value, "type");
                Map<String, Object> properties = map(value.getJSONObject("properties"));
                populateNameProperty(request, type, name, properties);
                result.add(new GraphEntityCandidate(scoped(request, localId), name, type,
                    strings(value.getJSONArray("aliases")), properties, evidence(request, value), confidence(value)));
            } catch (RuntimeException exception) {
                issues.add(malformed("MALFORMED_ENTITY", request, "entities", i, exception));
            }
        }
        return result;
    }

    /**
     * 逐项解析关系候选，关系端点在此阶段只做 mentionId 作用域转换。
     *
     * <p>端点是否存在、类型是否正确由后续 Schema 校验器判断。单个关系格式错误会记录为
     * {@code MALFORMED_RELATION}，不会影响同批次中的其他关系。</p>
     */
    private static List<GraphRelationCandidate> parseRelations(JSONArray array, GraphExtractionRequest request,
                                                               List<GraphExtractionIssue> issues) {
        if (array == null) return Collections.emptyList();
        int limit = request.getOptions().getMaxRelationsPerChunk();
        if (array.size() > limit)
            issues.add(new GraphExtractionIssue("RELATION_LIMIT", GraphExtractionIssue.Severity.WARNING,
                request.getChunkId(), "Relation candidates were truncated to " + limit));
        List<GraphRelationCandidate> result = new ArrayList<>();
        for (int i = 0; i < Math.min(array.size(), limit); i++) {
            try {
                JSONObject value = array.getJSONObject(i);
                result.add(new GraphRelationCandidate(scoped(request, required(value, "sourceMentionId")),
                    required(value, "type"), scoped(request, required(value, "targetMentionId")),
                    rank(value), map(value.getJSONObject("properties")), evidence(request, value),
                    confidence(value), assertion(value)));
            } catch (RuntimeException exception) {
                issues.add(malformed("MALFORMED_RELATION", request, "relations", i, exception));
            }
        }
        return result;
    }

    /**
     * 从候选 JSON 构造来源证据。
     *
     * <p>模型未同时给出有效的起止偏移时统一降级为未知值 {@code -1/-1}；完整偏移是否越界、
     * 是否精确对应 quote，由校验器结合当前 Chunk 原文判断。</p>
     */
    private static GraphEvidence evidence(GraphExtractionRequest request, JSONObject value) {
        boolean hasStart = value.containsKey("startOffset");
        boolean hasEnd = value.containsKey("endOffset");
        int start = hasStart && hasEnd ? exactInt(value.get("startOffset"), "startOffset") : -1;
        int end = hasStart && hasEnd ? exactInt(value.get("endOffset"), "endOffset") : -1;
        return new GraphEvidence(request.getDocumentId(), request.getChunkId(), optionalString(value, "evidence"),
            start, end, request.getMetadata());
    }

    /**
     * 当 Schema 声明字符串类型的 {@code name} 属性时，用候选实体主名称补齐模型遗漏值。
     *
     * <p>这是一项确定性的协议映射，不会读取 Schema 默认值，也不会为其他缺失属性伪造事实。</p>
     */
    private static void populateNameProperty(GraphExtractionRequest request, String type, String name,
                                             Map<String, Object> properties) {
        if (properties.containsKey("name")) return;
        for (com.agentsflex.graph.schema.GraphSchema.NodeType node : request.getSchema().getNodeTypes()) {
            if (!node.getLabel().equals(type)) continue;
            for (com.agentsflex.graph.schema.GraphSchema.Property property : node.getProperties()) {
                if (property.getName().equals("name")
                    && property.getType() == com.agentsflex.graph.schema.GraphSchema.PropertyType.STRING) {
                    properties.put("name", name);
                    return;
                }
            }
        }
    }

    /**
     * 读取必填数值置信度；范围和有限数约束由候选模型构造器统一执行。
     */
    private static double confidence(JSONObject value) {
        Object result = value.get("confidence");
        if (!(result instanceof Number))
            throw new GraphExtractionException("Missing or invalid candidate field: confidence");
        return ((Number) result).doubleValue();
    }

    /**
     * 解析关系断言类型。
     *
     * <p>字段缺失时按显式事实处理以兼容早期协议；字段存在但值非法时必须失败，禁止把未知值
     * 静默解释成推断关系。</p>
     */
    private static GraphAssertionType assertion(JSONObject candidate) {
        Object raw = candidate.get("assertionType");
        if (raw != null && !(raw instanceof String)) {
            throw new GraphExtractionException("Invalid assertionType value type");
        }
        String value = (String) raw;
        if (value == null || value.trim().isEmpty()) return GraphAssertionType.EXPLICIT;
        try {
            return GraphAssertionType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new GraphExtractionException("Invalid assertionType: " + value, exception);
        }
    }

    /**
     * 给模型响应内的局部 mentionId 添加 Chunk 作用域，避免跨分段键冲突。
     */
    private static String scoped(GraphExtractionRequest request, String localId) {
        return request.getChunkId() + "::" + localId;
    }

    /**
     * 读取并裁剪必填字符串字段。
     */
    private static String required(JSONObject value, String name) {
        if (value == null) throw new GraphExtractionException("Candidate must be a JSON object");
        Object raw = value.get(name);
        if (!(raw instanceof String)) throw new GraphExtractionException("Missing candidate field: " + name);
        String result = (String) raw;
        if (result == null || result.trim().isEmpty())
            throw new GraphExtractionException("Missing candidate field: " + name);
        return result.trim();
    }

    /**
     * 把可选 JSON 数组转换为非空字符串列表；非字符串元素属于候选协议错误。
     */
    private static List<String> strings(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object value : array) {
            if (!(value instanceof String)) throw new GraphExtractionException("aliases must contain only strings");
            String alias = ((String) value).trim();
            if (!alias.isEmpty()) result.add(alias);
        }
        return result;
    }

    /**
     * 读取可选字符串字段；字段存在但不是字符串时拒绝当前候选。
     */
    private static String optionalString(JSONObject value, String name) {
        Object result = value.get(name);
        if (result == null) return "";
        if (!(result instanceof String)) throw new GraphExtractionException("Invalid candidate field: " + name);
        return (String) result;
    }

    /**
     * 读取可选关系 rank，要求是不会溢出 long 的整数；缺失时使用 0。
     */
    private static long rank(JSONObject value) {
        if (!value.containsKey("rank")) return 0L;
        Object result = value.get("rank");
        if (!(result instanceof Number)) throw new GraphExtractionException("Invalid candidate field: rank");
        try {
            return new BigDecimal(result.toString()).longValueExact();
        } catch (ArithmeticException exception) {
            throw new GraphExtractionException("Relation rank must be an exact 64-bit integer", exception);
        }
    }

    /**
     * 读取不会溢出 int 的精确整数偏移。
     */
    private static int exactInt(Object value, String name) {
        if (!(value instanceof Number)) throw new GraphExtractionException("Invalid candidate field: " + name);
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException exception) {
            throw new GraphExtractionException(name + " must be an exact 32-bit integer", exception);
        }
    }

    /**
     * 把可选 JSON 对象复制为保持输入顺序的可变映射，供候选构造器建立只读快照。
     */
    private static Map<String, Object> map(JSONObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (object != null) result.putAll(object);
        return result;
    }

    /**
     * 为单个损坏候选创建稳定、可定位的结构化问题。
     */
    private static GraphExtractionIssue malformed(String code, GraphExtractionRequest request, String arrayName,
                                                  int index, RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage();
        return new GraphExtractionIssue(code, GraphExtractionIssue.Severity.ERROR,
            request.getChunkId() + "::" + arrayName + "[" + index + "]", message);
    }
}
