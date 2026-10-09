package com.agentsflex.graph.tools;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.query.GraphPageRequest;
import com.agentsflex.graph.query.GraphPageResult;
import com.agentsflex.graph.query.GraphQuery;
import com.agentsflex.graph.query.GraphQueryParseException;
import com.agentsflex.graph.query.GraphQueryParser;
import com.agentsflex.graph.query.ParsedGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面向 Agent 的渐进式知识图谱检索 Tool 集合。
 *
 * <p>完整调用流程分为三步：</p>
 * <ol>
 *   <li>{@code listKnowledgeGraphTypes}：发现知识源中的节点和边类型摘要；</li>
 *   <li>{@code describeKnowledgeGraphTypes}：只展开本次查询相关类型的属性；</li>
 *   <li>{@code queryKnowledgeGraph}：执行经过 Schema 校验的只读 Portable Query。</li>
 * </ol>
 *
 * <p>知识源必须由应用显式注册。本类不会枚举数据库连接或物理空间，也不会把原生 Cypher、nGQL
 * 直接交给后端执行。</p>
 */
public final class KnowledgeGraphTools {
    /**
     * 后端异常的完整信息只进入服务日志。
     */
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeGraphTools.class);
    /**
     * 所有模型可见软错误的统一前缀。
     */
    private static final String ERROR_PREFIX = "Error: ";
    /**
     * 单次 Schema 详情请求默认允许展开的类型总数。
     */
    private static final int DEFAULT_MAX_SCHEMA_ELEMENTS = 10;

    /**
     * 按精确逻辑名称索引的不可变知识源白名单。
     */
    private final Map<String, KnowledgeGraphSource> sources;
    /**
     * 单次详情披露允许请求的节点类型和边类型总数。
     */
    private final int maxSchemaElements;

    /**
     * 使用默认 Schema 披露上限创建 Tool 集合。
     *
     * @param sources 应用显式允许模型访问的知识源
     */
    public KnowledgeGraphTools(List<KnowledgeGraphSource> sources) {
        this(sources, DEFAULT_MAX_SCHEMA_ELEMENTS);
    }

    /**
     * 创建 Tool 集合并冻结知识源白名单。
     *
     * @param sources           应用显式允许模型访问的知识源
     * @param maxSchemaElements 单次详情请求允许展开的类型总数
     */
    public KnowledgeGraphTools(List<KnowledgeGraphSource> sources, int maxSchemaElements) {
        if (maxSchemaElements <= 0) {
            throw new IllegalArgumentException("max schema elements must be positive");
        }
        Map<String, KnowledgeGraphSource> values = new LinkedHashMap<>();
        if (sources != null) {
            for (KnowledgeGraphSource source : sources) {
                if (source == null) {
                    continue;
                }
                if (values.put(source.getName(), source) != null) {
                    throw new IllegalArgumentException("duplicate knowledge source: " + source.getName());
                }
            }
        }
        this.sources = Collections.unmodifiableMap(values);
        this.maxSchemaElements = maxSchemaElements;
    }

    /**
     * 动态创建第一阶段类型发现 Tool。
     *
     * <p>该 Tool 无法仅靠注解生成，因为参数枚举和描述中的知识源列表必须来自当前实例的白名单。
     * Tool 描述只包含逻辑名称和说明，不包含连接信息或物理数据库凭据。</p>
     *
     * @return 带动态知识源枚举的 listKnowledgeGraphTypes Tool
     */
    public Tool buildListKnowledgeGraphTypesTool() {
        String description = "[Knowledge retrieval - Step 1] List the node and edge types available in one knowledge source.\n\n"
            + "Required workflow:\n" + "1. Call listKnowledgeGraphTypes.\n"
            + "2. Call describeKnowledgeGraphTypes for only the relevant types.\n"
            + "3. Call queryKnowledgeGraph with a portable MATCH query.\n\n"
            + "Never invent source names, node labels, edge types, or properties.\n" + availableSourcesDescription();

        Parameter source = Parameter.builder()
            .name("knowledgeSourceName")
            .type("string")
            .description("Exact logical source name from available_knowledge_sources.")
            .enums(sources.keySet().toArray(new String[0]))
            .required(true)
            .build();

        return Tool.builder()
            .name("listKnowledgeGraphTypes")
            .description(description)
            .addParameter(source)
            .function(arguments -> {
                try {
                    KnowledgeGraphSource target = requireSource(
                        asString(arguments == null ? null : arguments.get("knowledgeSourceName")));
                    return GraphSchemaFormatter.summary(target);
                } catch (KnowledgeGraphToolException error) {
                    return error(error.getCode(), error.getMessage());
                } catch (RuntimeException error) {
                    LOG.warn("Could not list knowledge graph types", error);
                    return error("SCHEMA_DISCLOSURE_FAILED", "The knowledge graph schema could not be listed");
                }
            })
            .build();
    }

    /**
     * 按需展开少量节点或边类型的属性定义。
     *
     * @param knowledgeSourceName 第一步返回的精确知识源名称
     * @param nodeLabels          需要展开的节点标签，可以为空集合
     * @param edgeTypes           需要展开的边类型，可以为空集合
     * @return Schema 详情 JSON，失败时返回稳定软错误
     */
    @ToolDef(name = "describeKnowledgeGraphTypes", description = "[Knowledge retrieval - Step 2] Expand only the selected node and edge types before writing a graph query.\n\n"
        + "Names must come from listKnowledgeGraphTypes. The result includes exact property names, portable types, "
        + "required flags, descriptions, enum values, and edge endpoints. Do not request unrelated types and do not "
        + "invent properties.")
    public String describeKnowledgeGraphTypes(
        @ToolParam(name = "knowledgeSourceName", required = true, description = "Exact logical source name from listKnowledgeGraphTypes.") String knowledgeSourceName,
        @ToolParam(name = "nodeLabels", description = "Node labels to expand. Use an empty array when no node type is needed.") List<String> nodeLabels,
        @ToolParam(name = "edgeTypes", description = "Edge types to expand. Use an empty array when no edge type is needed.") List<String> edgeTypes) {
        try {
            KnowledgeGraphSource source = requireSource(knowledgeSourceName);
            List<String> nodes = distinct(nodeLabels);
            List<String> edges = distinct(edgeTypes);
            if (nodes.isEmpty() && edges.isEmpty()) {
                throw new KnowledgeGraphToolException("INVALID_ARGUMENT", "At least one node label or edge type is required");
            }
            if (nodes.size() + edges.size() > maxSchemaElements) {
                throw new KnowledgeGraphToolException("SCHEMA_DISCLOSURE_LIMIT",
                        "At most " + maxSchemaElements + " schema elements may be expanded in one call");
            }
            // 先完成全部名称校验，避免详情格式化到一半才发现未知类型。
            for (String label : nodes) {
                GraphSchemaFormatter.findNode(source.getSchema(), label);
            }
            for (String type : edges) {
                GraphSchemaFormatter.findEdge(source.getSchema(), type);
            }
            return GraphSchemaFormatter.details(source, nodes, edges);
        } catch (KnowledgeGraphToolException error) {
            return error(error.getCode(), error.getMessage());
        } catch (RuntimeException error) {
            LOG.warn("Could not describe knowledge graph types for source {}", knowledgeSourceName, error);
            return error("SCHEMA_DISCLOSURE_FAILED", "The requested graph schema details could not be returned");
        }
    }

    /**
     * 解析、校验并执行一个只读 Portable Query。
     *
     * <p>数据库执行前会依次完成查询种类、内联分页、公开 Schema 和跳数校验。分页由 Tool 参数统一
     * 控制，执行选项始终强制只读并固定到知识源配置的空间。</p>
     *
     * @param knowledgeSourceName 第一步返回的精确知识源名称
     * @param expression          Portable Graph Query 表达式
     * @param parameters          表达式中的命名参数值
     * @param pageSize            当前页大小，null 表示使用知识源默认值
     * @param cursor              上一页返回的不透明游标，第一页传 null 或空字符串
     * @return 规范化结果 JSON，失败时返回稳定软错误
     */
    @ToolDef(name = "queryKnowledgeGraph", description = "[Knowledge retrieval - Step 3] Execute one read-only portable graph query.\n\n"
        + "Call listKnowledgeGraphTypes and describeKnowledgeGraphTypes first. Only a single linear MATCH query is "
        + "accepted; native Cypher/nGQL, OPTIONAL MATCH, UNION, writes, untyped nodes, and untyped edges are prohibited. "
        + "Use :name parameters for dynamic values and pass those values in parameters. Use explicit RETURN projections. "
        + "Pagination is controlled by pageSize and cursor, so omit SKIP and LIMIT from the expression.")
    public String queryKnowledgeGraph(
        @ToolParam(name = "knowledgeSourceName", required = true, description = "Exact logical source name from listKnowledgeGraphTypes.") String knowledgeSourceName,
        @ToolParam(name = "expression", required = true, description = "Portable graph query using MATCH, optional WHERE, RETURN, GROUP BY and ORDER BY clauses.") String expression,
        @ToolParam(name = "parameters", required = true, description = "JSON object of values bound to :name references. Use an empty object when there are no parameters.") Map<String, Object> parameters,
        @ToolParam(name = "pageSize", description = "Maximum records in this page. Omit to use the source default.") Integer pageSize,
        @ToolParam(name = "cursor", description = "Opaque nextCursor returned by the preceding call. Omit for the first page.") String cursor) {
        KnowledgeGraphSource source;
        try {
            source = requireSource(knowledgeSourceName);
            int resolvedPageSize = resolvePageSize(source, pageSize);
            ParsedGraphQuery parsed = GraphQueryParser.parse(
                expression,
                parameters == null ? Collections.<String, Object>emptyMap() : parameters);
            GraphQuery parsedQuery = parsed.getGraphQuery();
            if (!(parsedQuery instanceof TraversalQuery)) {
                throw new KnowledgeGraphToolException("UNSUPPORTED_QUERY", "Only a single linear MATCH query is supported by this tool");
            }
            TraversalQuery query = (TraversalQuery) parsedQuery;
            if (query.getSkip() != 0 || query.getLimit() != 100) {
                throw new KnowledgeGraphToolException("QUERY_NOT_ALLOWED", "Omit SKIP and LIMIT; use pageSize and cursor for pagination");
            }
            // Schema 校验必须发生在构造分页请求和访问数据库之前。
            GraphSchemaQueryValidator.validate(query, source.getSchema(), source.getMaxHops());
            GraphPageRequest page = cursor == null || cursor.trim().isEmpty()
                    ? GraphPageRequest.of(0, resolvedPageSize)
                    : GraphPageRequest.after(cursor.trim(), resolvedPageSize);
            GraphPageResult result = source.getQueryExecutor().executePage(query, page, source.optionsForPage(resolvedPageSize));
            return GraphResultFormatter.format(source, result);
        } catch (KnowledgeGraphToolException error) {
            return error(error.getCode(), error.getMessage());
        } catch (GraphQueryParseException error) {
            return error("INVALID_QUERY", error.getKind() + ": " + error.getMessage());
        } catch (GraphException error) {
            LOG.warn("Knowledge graph query failed with code {} for source {}", error.getCode(), knowledgeSourceName, error);
            return graphError(error);
        } catch (IllegalArgumentException error) {
            return error("INVALID_ARGUMENT", error.getMessage());
        } catch (RuntimeException error) {
            LOG.warn("Knowledge graph query failed for source {}", knowledgeSourceName, error);
            return error("QUERY_FAILED", "The knowledge graph query could not be completed");
        }
    }

    /**
     * @return KnowledgeGraphTools Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 按大小写敏感的精确名称从白名单中选择知识源。
     */
    private KnowledgeGraphSource requireSource(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new KnowledgeGraphToolException("INVALID_ARGUMENT", "knowledgeSourceName must not be blank");
        }
        KnowledgeGraphSource source = sources.get(name);
        if (source == null) {
            throw new KnowledgeGraphToolException("UNKNOWN_KNOWLEDGE_SOURCE",
                    "Unknown knowledge source '" + ToolText.clean(name, 200) + "'; available sources: " + sources.keySet());
        }
        return source;
    }

    /**
     * 解析页大小，并确保模型不能突破知识源配置的上限。
     */
    private int resolvePageSize(KnowledgeGraphSource source, Integer pageSize) {
        int value = pageSize == null ? source.getDefaultPageSize() : pageSize;
        if (value <= 0 || value > source.getMaxPageSize()) {
            throw new KnowledgeGraphToolException("INVALID_ARGUMENT", "pageSize must be between 1 and " + source.getMaxPageSize());
        }
        return value;
    }

    /**
     * 生成嵌入第一阶段 Tool 描述的知识源清单。
     *
     * <p>知识源描述属于应用输入，写入 XML 风格片段前必须清理并转义。</p>
     */
    private String availableSourcesDescription() {
        StringBuilder value = new StringBuilder("<available_knowledge_sources>\n");
        for (KnowledgeGraphSource source : sources.values()) {
            value.append("  <knowledge_source>\n").append("    <name>").append(ToolText.xml(source.getName(), 200)).append("</name>\n")
                .append("    <description>").append(ToolText.xml(source.getDescription(), 500)).append("</description>\n")
                .append("  </knowledge_source>\n");
        }
        if (sources.isEmpty()) {
            value.append("  <!-- No knowledge sources are configured. -->\n");
        }
        return value.append("</available_knowledge_sources>").toString();
    }

    /**
     * 保留请求顺序地去重 Schema 类型名称，同时拒绝空名称。
     */
    private static List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new KnowledgeGraphToolException("INVALID_ARGUMENT", "Schema element names must not be blank");
            }
            result.add(value);
        }
        return new ArrayList<>(result);
    }

    /**
     * 把 Graph API 异常映射为对模型稳定、且不泄露后端细节的软错误。
     */
    private static String graphError(GraphException error) {
        GraphErrorCode code = error.getCode();
        if (code == GraphErrorCode.QUERY_TIMEOUT) {
            return error(code.name(), "The knowledge graph query timed out");
        }
        if (code == GraphErrorCode.CONNECTION_FAILED) {
            return error(code.name(), "The knowledge graph backend is unavailable");
        }
        if (code == GraphErrorCode.SPACE_NOT_FOUND) {
            return error(code.name(), "The configured knowledge graph space is unavailable");
        }
        if (code == GraphErrorCode.UNSUPPORTED_FEATURE) {
            return error(code.name(), ToolText.clean(error.getMessage(), 500));
        }
        return error(code.name(), "The knowledge graph query failed");
    }

    /**
     * 生成统一的模型可见软错误文本。
     */
    private static String error(String code, String message) {
        return ERROR_PREFIX + code + ": " + ToolText.clean(message, 500);
    }

    /**
     * 将编程式 Tool 参数安全转换为字符串。
     */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 按注册顺序组装知识源和三个渐进式 Tool。
     */
    public static final class Builder {
        private final List<KnowledgeGraphSource> sources = new ArrayList<>();
        private int maxSchemaElements = DEFAULT_MAX_SCHEMA_ELEMENTS;

        /**
         * @param source 待注册知识源
         * @return 当前 Builder
         */
        public Builder addSource(KnowledgeGraphSource source) {
            if (source != null) {
                sources.add(source);
            }
            return this;
        }

        /**
         * @param sources 待注册知识源列表
         * @return 当前 Builder
         */
        public Builder addSources(List<KnowledgeGraphSource> sources) {
            if (sources != null) {
                this.sources.addAll(sources);
            }
            return this;
        }

        /**
         * @param maxSchemaElements 单次 Schema 详情披露上限
         * @return 当前 Builder
         */
        public Builder maxSchemaElements(int maxSchemaElements) {
            this.maxSchemaElements = maxSchemaElements;
            return this;
        }

        /**
         * @return 冻结知识源白名单后的 Tool 容器
         */
        public KnowledgeGraphTools build() {
            return new KnowledgeGraphTools(sources, maxSchemaElements);
        }

        /**
         * 创建按渐进式流程排列的三个 Tool。
         *
         * @return 不可变 Tool 列表，第一项始终是动态发现 Tool
         */
        public List<Tool> buildTools() {
            KnowledgeGraphTools tools = build();
            List<Tool> result = new ArrayList<>();
            result.add(tools.buildListKnowledgeGraphTypesTool());
            result.addAll(ToolScanner.scan(tools));
            return Collections.unmodifiableList(result);
        }
    }
}
