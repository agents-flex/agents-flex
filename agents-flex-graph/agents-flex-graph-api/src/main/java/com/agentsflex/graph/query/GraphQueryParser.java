package com.agentsflex.graph.query;

import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 解析 Agents-Flex Graph 公共查询语言的线性遍历子集。
 *
 * <p>当前支持 {@code MATCH}、{@code WHERE}、{@code RETURN}、{@code GROUP BY}、
 * {@code HAVING}、{@code ORDER BY}、{@code SKIP}、{@code LIMIT} 以及顶层
 * {@code UNION}/{@code UNION ALL}。解析器只生成统一 AST，不生成 Cypher、nGQL 或其他后端方言，
 * 因此同一个表达式可以继续交给不同适配器编译。</p>
 *
 * <p>值推荐使用命名参数，例如 {@code WHERE p.age >= :minAge}，调用方通过
 * {@link #parse(String, Map)} 传值。标签、边类型、别名和属性名属于结构标识符，始终经过
 * {@link GraphIdentifiers} 校验，不能用参数替代。</p>
 */
public final class GraphQueryParser {
    /**
     * 工具类不允许实例化。
     */
    private GraphQueryParser() {
    }

    /**
     * 解析只包含字面量的公共查询。
     *
     * @param expression 查询表达式
     * @return 解析结果
     * @throws GraphQueryParseException 查询为空、语法错误或统一查询校验失败时抛出
     */
    public static ParsedGraphQuery parse(String expression) {
        return parse(expression, Collections.<String, Object>emptyMap());
    }

    /**
     * 解析公共查询并绑定命名参数。
     *
     * @param expression 查询表达式
     * @param parameters 命名参数；集合参数会被复制，不会被查询持有可变引用
     * @return 已校验的统一查询
     * @throws GraphQueryParseException 查询为空、语法错误或缺少命名参数时抛出
     */
    public static ParsedGraphQuery parse(String expression, Map<String, ?> parameters) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new GraphQueryParseException(expression, 0, "graph query must not be blank");
        }
        Map<String, Object> snapshot = immutableParameters(parameters);
        String trimmed = expression.trim();
        if (trimmed.regionMatches(true, 0, "OPTIONAL MATCH", 0, 14)
            && (trimmed.length() == 14 || Character.isWhitespace(trimmed.charAt(14)))) {
            ParsedGraphQuery parsed = parse("MATCH " + trimmed.substring(14).trim(), snapshot);
            return new ParsedGraphQuery(expression, GraphOptionalQuery.of(parsed.getQuery()), snapshot);
        }
        UnionParts union = splitUnion(expression);
        if (union != null) {
            List<TraversalQuery> branches = new ArrayList<>();
            for (String branch : union.branches) {
                branches.add(parse(branch, snapshot).getQuery());
            }
            GraphUnionQuery query = union.all
                ? GraphUnionQuery.unionAll(branches.get(0), branches.subList(1, branches.size()).toArray(new TraversalQuery[0]))
                : GraphUnionQuery.union(branches.get(0), branches.subList(1, branches.size()).toArray(new TraversalQuery[0]));
            return new ParsedGraphQuery(expression, query, snapshot);
        }
        Parser parser = new Parser(expression, snapshot, false);
        TraversalQuery query;
        try {
            query = parser.parseQuery();
        } catch (GraphQueryParseException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new GraphQueryParseException(expression, 0, error.getMessage(), GraphQueryErrorKind.SEMANTIC);
        }
        return new ParsedGraphQuery(expression, query, snapshot);
    }

    /**
     * 识别顶层 UNION/UNION ALL，忽略字符串、属性 Map 和括号内部的同名文本。
     */
    private static UnionParts splitUnion(String expression) {
        List<String> branches = new ArrayList<>();
        int depth = 0, start = 0;
        boolean quoted = false;
        char quote = 0;
        boolean all = false;
        int unionStart = -1;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                if (c == quote && (i == 0 || expression.charAt(i - 1) != '\\')) quoted = false;
                continue;
            }
            if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                continue;
            }
            if (c == '(' || c == '{' || c == '[') {
                depth++;
                continue;
            }
            if (c == ')' || c == '}' || c == ']') {
                depth--;
                continue;
            }
            if (depth != 0 || !isBoundary(expression, i - 1) || !startsWord(expression, i, "UNION")) continue;
            int end = i + 5;
            int keywordEnd = end;
            while (keywordEnd < expression.length() && Character.isWhitespace(expression.charAt(keywordEnd)))
                keywordEnd++;
            boolean branchAll = startsWord(expression, keywordEnd, "ALL")
                && isBoundary(expression, keywordEnd + 3);
            if (branchAll) end = keywordEnd + 3;
            if (!isBoundary(expression, end)) continue;
            if (unionStart >= 0 && branchAll != all)
                throw new GraphQueryParseException(expression, i, "UNION and UNION ALL cannot be mixed");
            if (unionStart < 0) all = branchAll;
            branches.add(expression.substring(start, i).trim());
            start = end;
            unionStart = i;
            i = end - 1;
        }
        if (unionStart < 0) return null;
        branches.add(expression.substring(start).trim());
        if (branches.size() < 2)
            throw new GraphQueryParseException(expression, unionStart, "UNION requires two queries");
        return new UnionParts(branches, all);
    }

    private static boolean startsWord(String text, int offset, String word) {
        return offset >= 0 && offset + word.length() <= text.length()
            && text.regionMatches(true, offset, word, 0, word.length());
    }

    private static boolean isBoundary(String text, int offset) {
        return offset < 0 || offset >= text.length() || !Character.isLetterOrDigit(text.charAt(offset)) && text.charAt(offset) != '_';
    }

    private static final class UnionParts {
        private final List<String> branches;
        private final boolean all;

        private UnionParts(List<String> branches, boolean all) {
            this.branches = branches;
            this.all = all;
        }
    }

    /**
     * 只解析查询结构并保留命名参数引用，不要求本次提供参数值。
     *
     * @param expression 查询模板
     * @return 可重复绑定的查询模板
     */
    public static GraphQueryTemplate parseTemplate(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new GraphQueryParseException(expression, 0, "graph query must not be blank");
        }
        Parser parser = new Parser(expression, Collections.<String, Object>emptyMap(), true);
        try {
            return new GraphQueryTemplate(expression, parser.parseQuery());
        } catch (GraphQueryParseException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new GraphQueryParseException(expression, 0, error.getMessage(), GraphQueryErrorKind.SEMANTIC);
        }
    }

    /**
     * 冻结参数容器及其嵌套集合，避免解析完成后调用方修改原始 Map 影响查询语义。
     *
     * <p>参数名本身属于结构标识符，因此也使用与别名、属性名相同的安全规则校验。</p>
     */
    private static Map<String, Object> immutableParameters(Map<String, ?> parameters) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (parameters == null) return result;
        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            GraphIdentifiers.requireValid(entry.getKey(), "query parameter");
            result.put(entry.getKey(), immutableValue(entry.getValue()));
        }
        return result;
    }

    /**
     * 递归复制数组、集合和 Map 参数。
     *
     * <p>数组统一转换成只读 List，便于不同后端获得一致的绑定值形态；普通不可识别对象
     * 作为业务值原样保留。</p>
     */
    private static Object immutableValue(Object value) {
        if (value == null) return null;
        // 模板解析阶段的引用是 AST 语义节点，不能被当作普通集合或业务对象展开。
        if (value instanceof GraphParameterReference) return value;
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(immutableValue(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Collection<?>) value) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) copy.add(immutableValue(Array.get(value, i)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * 查询递归下降解析器。
     */
    private static final class Parser {
        /**
         * 待解析的原始查询文本，用于生成错误位置和诊断信息。
         */
        private final String source;
        /**
         * 已经深度冻结的命名参数快照。
         */
        private final Map<String, Object> parameters;
        /**
         * 是否允许将未绑定参数保留为模板引用。
         */
        private final boolean allowMissingParameters;
        /**
         * Lexer 生成的 token 序列，末尾始终包含 END token。
         */
        private final List<Token> tokens;
        /**
         * 当前待消费 token 在 {@link #tokens} 中的下标。
         */
        private int index;
        /**
         * 匿名节点别名的递增序号。
         */
        private int anonymousNode;
        /**
         * 匿名边别名的递增序号。
         */
        private int anonymousEdge;
        /**
         * COUNT(*) 归一化时使用的匹配起始节点别名。
         */
        private String matchStartAlias;

        /**
         * 创建解析器，并在解析开始前一次性完成词法分析。
         */
        private Parser(String source, Map<String, Object> parameters, boolean allowMissingParameters) {
            this.source = source;
            this.parameters = parameters;
            this.allowMissingParameters = allowMissingParameters;
            this.tokens = new Lexer(source).lex();
        }

        /**
         * 按 MATCH -> WHERE -> RETURN -> ORDER BY -> SKIP -> LIMIT 的固定顺序解析查询。
         *
         * <p>解析结果直接交给 {@link TraversalQuery.Builder}，由统一 AST 再做别名、
         * 投影和分页边界校验。</p>
         */
        private TraversalQuery parseQuery() {
            expectWord("MATCH");
            TraversalQuery.NodePattern start = parseNode();
            matchStartAlias = start.getAlias();
            TraversalQuery.Builder builder = TraversalQuery.from(start);
            // 连续消费线性边-节点模式，保持查询结构与后端无关。
            while (peek().type == TokenType.DASH || peek().type == TokenType.LEFT_ARROW) {
                TraversalQuery.EdgePattern edge = parseEdge();
                TraversalQuery.NodePattern node = parseNode();
                builder.traverse(edge, node);
            }
            if (acceptWord("WHERE")) builder.where(parseFilterOr());
            if (acceptWord("RETURN")) parseReturn(builder);
            else fail(peek(), "expected RETURN clause");
            if (acceptWord("GROUP")) {
                expectWord("BY");
                parseGroupBy(builder);
            }
            if (acceptWord("HAVING")) builder.having(parseFilterOr());
            if (acceptWord("ORDER")) {
                expectWord("BY");
                parseOrderBy(builder);
            }
            if (acceptWord("SKIP")) builder.skip(parseInteger("SKIP value"));
            if (acceptWord("LIMIT")) builder.limit(parseInteger("LIMIT value"));
            expect(TokenType.END, "unexpected token after query");
            return builder.build();
        }

        /**
         * 解析形如 {@code (alias:Label)}、{@code (alias)} 或 {@code (:Label)} 的节点模式。
         */
        private TraversalQuery.NodePattern parseNode() {
            expect(TokenType.LEFT_PAREN, "expected node pattern");
            String alias = null;
            List<String> labels = new ArrayList<>();
            if (peek().type == TokenType.IDENT && !peekWord("RETURN") && !peekWord("WHERE")) {
                alias = next().text;
            }
            while (accept(TokenType.COLON)) labels.add(identifier("node label"));
            Map<String, Object> properties = parseProperties("node property");
            expect(TokenType.RIGHT_PAREN, "expected ')' after node pattern");
            // 匿名节点也必须有稳定别名，后续投影、过滤和编译器才能引用它。
            if (alias == null) alias = "_n" + anonymousNode++;
            return labels.isEmpty() ? TraversalQuery.NodePattern.anyNode(alias, properties)
                : TraversalQuery.NodePattern.node(alias, labels, properties);
        }

        /**
         * 解析边模式并归一化方向。
         *
         * <p>{@code -[...]->} 为 OUT，{@code <-[...] -} 为 IN，{@code -[...] -} 为 BOTH；
         * 没有类型时保留 {@code null}，表示匹配任意边类型。</p>
         */
        private TraversalQuery.EdgePattern parseEdge() {
            boolean incoming = accept(TokenType.LEFT_ARROW);
            if (!incoming) expect(TokenType.DASH, "expected '-' before edge pattern");
            expect(TokenType.LEFT_BRACKET, "expected '[' after '-'");
            String alias = null;
            List<String> types = new ArrayList<>();
            if (peek().type == TokenType.IDENT) alias = next().text;
            if (accept(TokenType.COLON)) {
                types.add(identifier("edge type"));
                while (accept(TokenType.PIPE)) types.add(identifier("edge type"));
            }
            Map<String, Object> properties = parseProperties("edge property");
            int min = 1;
            int max = 1;
            if (accept(TokenType.STAR)) {
                min = parseInteger("minimum hop");
                if (accept(TokenType.DOT_DOT)) max = parseInteger("maximum hop");
                else max = min;
            }
            expect(TokenType.RIGHT_BRACKET, "expected ']' after edge pattern");
            boolean trailingDash = accept(TokenType.DASH);
            if (!trailingDash && !incoming) fail(peek(), "expected '-' after edge pattern");
            // 公共语法使用 -> 和 <- 表示方向；内部只保留方向，不把箭头字符带入后端方言。
            boolean outgoingArrow = trailingDash && accept(TokenType.GT);
            if (incoming && outgoingArrow) fail(peek(), "edge direction cannot point both ways");
            if (alias == null) alias = "_e" + anonymousEdge++;
            TraversalQuery.Direction direction = incoming ? TraversalQuery.Direction.IN
                : (outgoingArrow ? TraversalQuery.Direction.OUT : TraversalQuery.Direction.BOTH);
            return (types.isEmpty()
                ? TraversalQuery.EdgePattern.edge(alias, (String) null, direction, properties)
                : TraversalQuery.EdgePattern.edge(alias, types, direction, properties)).hops(min, max);
        }

        /**
         * 解析模式中的属性 Map，例如 {@code {status: 'ACTIVE', age: 18}}。
         */
        private Map<String, Object> parseProperties(String description) {
            if (!accept(TokenType.LEFT_BRACE)) return Collections.emptyMap();
            Map<String, Object> properties = new LinkedHashMap<>();
            if (!accept(TokenType.RIGHT_BRACE)) {
                do {
                    String name = identifier(description);
                    expect(TokenType.COLON, "expected ':' after " + description);
                    if (properties.containsKey(name)) fail(peek(), "duplicate " + description + " key: " + name);
                    properties.put(name, value(description + " value"));
                } while (accept(TokenType.COMMA));
                expect(TokenType.RIGHT_BRACE, "expected '}' after " + description + " map");
            }
            return properties;
        }

        /**
         * 解析 RETURN 投影列表，并处理 RETURN 级别的 DISTINCT 修饰符。
         */
        private void parseReturn(TraversalQuery.Builder builder) {
            // DISTINCT 属于 RETURN 子句修饰符，而不是投影列本身。
            if (acceptWord("DISTINCT")) builder.distinct(true);
            do {
                if (acceptWord("PATH")) {
                    builder.select(TraversalQuery.Projection.path(parseAlias("path")));
                    continue;
                }
                if (isAggregateFunction(peek())) {
                    parseAggregate(builder, next().upper);
                } else {
                    String alias = identifier("projection alias");
                    String property = null;
                    if (accept(TokenType.DOT)) property = identifier("projection property");
                    String output = parseAlias(property == null ? alias : property);
                    builder.select(property == null
                        ? TraversalQuery.Projection.entity(alias, output)
                        : TraversalQuery.Projection.property(alias, property, output));
                }
            } while (accept(TokenType.COMMA));
        }

        /**
         * 解析 COUNT、SUM、AVG、MIN、MAX 等统一聚合函数。
         *
         * <p>COUNT(*) 不把星号直接暴露给后端，而是归一化为起始节点别名的 COUNT，
         * 这样 Neo4j 和 Nebula 都能复用同一个 AST。</p>
         */
        private void parseAggregate(TraversalQuery.Builder builder, String functionName) {
            expect(TokenType.LEFT_PAREN, "expected '(' after " + functionName);
            boolean distinct = acceptWord("DISTINCT");
            boolean wildcard = accept(TokenType.STAR);
            if (wildcard && !"COUNT".equals(functionName)) {
                fail(peek(), functionName + " requires an aggregate property");
            }
            if (wildcard && distinct && matchStartAlias == null) {
                fail(peek(), "COUNT DISTINCT wildcard requires a MATCH node");
            }
            String alias = wildcard ? matchStartAlias : identifier(functionName + " alias");
            String property = null;
            if (!wildcard && accept(TokenType.DOT)) property = identifier(functionName + " property");
            expect(TokenType.RIGHT_PAREN, "expected ')' after " + functionName);
            if (distinct && !"COUNT".equals(functionName)) {
                fail(peek(), functionName + " DISTINCT is not portable");
            }
            TraversalQuery.AggregateFunction function;
            if ("COUNT".equals(functionName)) {
                function = distinct ? TraversalQuery.AggregateFunction.COUNT_DISTINCT
                    : TraversalQuery.AggregateFunction.COUNT;
            } else if ("SUM".equals(functionName)) {
                function = TraversalQuery.AggregateFunction.SUM;
            } else if ("AVG".equals(functionName)) {
                function = TraversalQuery.AggregateFunction.AVG;
            } else if ("MIN".equals(functionName)) {
                function = TraversalQuery.AggregateFunction.MIN;
            } else {
                function = TraversalQuery.AggregateFunction.MAX;
            }
            String output = parseAlias(wildcard ? "count" : (property == null ? alias : property));
            builder.select(TraversalQuery.Projection.aggregate(function, alias, property, output));
        }

        /**
         * 判断当前 token 是否是公共 DSL 支持的聚合函数名。
         */
        private boolean isAggregateFunction(Token token) {
            return token.type == TokenType.WORD
                && ("COUNT".equals(token.upper) || "SUM".equals(token.upper)
                || "AVG".equals(token.upper) || "MIN".equals(token.upper)
                || "MAX".equals(token.upper));
        }

        /**
         * 读取可选的 AS 别名；未显式指定时使用投影属性或别名作为输出列名。
         */
        private String parseAlias(String fallback) {
            if (acceptWord("AS")) return identifier("projection alias");
            return fallback;
        }

        /**
         * 解析一个或多个属性排序项，默认方向为 ASC。
         */
        private void parseOrderBy(TraversalQuery.Builder builder) {
            do {
                String alias = identifier("sort alias");
                expect(TokenType.DOT, "expected '.' in ORDER BY");
                String property = identifier("sort property");
                TraversalQuery.SortDirection direction = acceptWord("DESC")
                    ? TraversalQuery.SortDirection.DESC : TraversalQuery.SortDirection.ASC;
                if (direction == TraversalQuery.SortDirection.ASC) acceptWord("ASC");
                builder.orderBy(new TraversalQuery.Sort(alias, property, direction));
            } while (accept(TokenType.COMMA));
        }

        /**
         * 解析聚合查询的 GROUP BY 属性列表。
         */
        private void parseGroupBy(TraversalQuery.Builder builder) {
            List<TraversalQuery.GroupKey> keys = new ArrayList<>();
            do {
                String alias = identifier("group alias");
                expect(TokenType.DOT, "expected '.' in GROUP BY");
                keys.add(new TraversalQuery.GroupKey(alias, identifier("group property")));
            } while (accept(TokenType.COMMA));
            builder.groupBy(keys.toArray(new TraversalQuery.GroupKey[0]));
        }

        /**
         * 解析最低优先级的 OR 条件。
         */
        private GraphFilter parseFilterOr() {
            GraphFilter result = parseFilterAnd();
            while (acceptWord("OR")) result = GraphFilter.or(result, parseFilterAnd());
            return result;
        }

        /**
         * 解析优先级高于 OR 的 AND 条件。
         */
        private GraphFilter parseFilterAnd() {
            GraphFilter result = parseFilterNot();
            while (acceptWord("AND")) result = GraphFilter.and(result, parseFilterNot());
            return result;
        }

        /**
         * 解析前置 NOT、括号分组或单个属性谓词。
         */
        private GraphFilter parseFilterNot() {
            if (acceptWord("NOT")) return GraphFilter.not(parseFilterNot());
            if (accept(TokenType.LEFT_PAREN)) {
                GraphFilter result = parseFilterOr();
                expect(TokenType.RIGHT_PAREN, "expected ')' after filter");
                return result;
            }
            String alias = identifier("filter alias");
            expect(TokenType.DOT, "expected '.' in filter");
            String property = identifier("filter property");
            if (acceptWord("IS")) {
                boolean not = acceptWord("NOT");
                expectWord("NULL");
                return not ? GraphFilter.isNotNull(alias, property) : GraphFilter.isNull(alias, property);
            }
            Token operator = next();
            if (operator.type == TokenType.WORD && "IN".equals(operator.upper)) {
                return GraphFilter.in(alias, property, collectionValue("IN values"));
            }
            if (operator.type == TokenType.WORD && "NOT".equals(operator.upper)) {
                expectWord("IN");
                return GraphFilter.notIn(alias, property, collectionValue("NOT IN values"));
            }
            if (operator.type == TokenType.WORD && "BETWEEN".equals(operator.upper)) {
                Object lower = value("BETWEEN lower bound");
                expectWord("AND");
                return GraphFilter.between(alias, property, lower, value("BETWEEN upper bound"));
            }
            if (operator.type == TokenType.WORD && "CONTAINS".equals(operator.upper)) {
                return GraphFilter.contains(alias, property, value("CONTAINS value"));
            }
            if (operator.type == TokenType.WORD && "STARTS".equals(operator.upper)) {
                expectWord("WITH");
                return GraphFilter.startsWith(alias, property, value("STARTS WITH value"));
            }
            if (operator.type == TokenType.WORD && "ENDS".equals(operator.upper)) {
                expectWord("WITH");
                return GraphFilter.endsWith(alias, property, value("ENDS WITH value"));
            }
            if (operator.type == TokenType.WORD && "REGEX".equals(operator.upper)) {
                return GraphFilter.regex(alias, property, value("REGEX value"));
            }
            GraphFilter.Operator op = operator(operator);
            return GraphFilter.predicate(alias, property, op, value("comparison value"));
        }

        /**
         * 解析 IN/NOT IN 的数组字面量或集合参数，并拒绝空集合。
         */
        private Collection<?> collectionValue(String message) {
            if (accept(TokenType.LEFT_BRACKET)) {
                List<Object> values = new ArrayList<>();
                if (!accept(TokenType.RIGHT_BRACKET)) {
                    do values.add(value(message)); while (accept(TokenType.COMMA));
                    expect(TokenType.RIGHT_BRACKET, "expected ']' after collection");
                }
                if (values.isEmpty()) fail(peek(), message + " must not be empty");
                return values;
            }
            Object parameter = value(message);
            if (parameter instanceof GraphParameterReference) {
                // 模板中的 IN :statuses 先保留一个引用节点，bind 时再恢复真实集合。
                return Collections.singletonList(parameter);
            }
            if (!(parameter instanceof Collection) && !(parameter != null && parameter.getClass().isArray())) {
                fail(peek(), message + " must be a collection parameter");
            }
            List<Object> values = new ArrayList<>();
            if (parameter instanceof Collection) values.addAll((Collection<?>) parameter);
            else for (int i = 0; i < Array.getLength(parameter); i++) values.add(Array.get(parameter, i));
            if (values.isEmpty()) fail(peek(), message + " must not be empty");
            return values;
        }

        /**
         * 解析比较值。
         *
         * <p>冒号在 Lexer 中始终是 COLON，只有在值位置才被解释为命名参数前缀，
         * 从而避免节点标签 {@code :Person} 与参数 {@code :person} 发生词法冲突。</p>
         */
        private Object value(String message) {
            Token token = next();
            // 冒号在词法层始终表示 COLON：在节点/边模式中它是标签分隔符，
            // 在值位置才表示命名参数前缀，从而避免 :Person 被误判为参数。
            if (token.type == TokenType.COLON) {
                Token name = next();
                if (name.type != TokenType.IDENT) fail(name, "expected parameter name");
                GraphIdentifiers.requireValid(name.text, "query parameter");
                if (!parameters.containsKey(name.text)) {
                    if (allowMissingParameters) return new GraphParameterReference(name.text);
                    fail(token, "missing parameter '" + name.text + "'");
                }
                return parameters.get(name.text);
            }
            if (token.type == TokenType.STRING) return token.text;
            if (token.type == TokenType.NUMBER) return token.text.contains(".")
                ? Double.valueOf(token.text) : Long.valueOf(token.text);
            if (token.type == TokenType.WORD) {
                if ("TRUE".equals(token.upper)) return Boolean.TRUE;
                if ("FALSE".equals(token.upper)) return Boolean.FALSE;
                if ("NULL".equals(token.upper)) return null;
            }
            fail(token, "expected " + message);
            return null;
        }

        /**
         * 将词法运算符映射为统一 GraphFilter 运算符。
         */
        private GraphFilter.Operator operator(Token token) {
            switch (token.type) {
                case EQ:
                    return GraphFilter.Operator.EQ;
                case NE:
                    return GraphFilter.Operator.NE;
                case GT:
                    return GraphFilter.Operator.GT;
                case GE:
                    return GraphFilter.Operator.GE;
                case LT:
                    return GraphFilter.Operator.LT;
                case LE:
                    return GraphFilter.Operator.LE;
                default:
                    fail(token, "expected comparison operator");
                    return GraphFilter.Operator.EQ;
            }
        }

        /**
         * 读取并校验结构标识符，拒绝可能造成后端注入的字符。
         */
        private String identifier(String description) {
            Token token = next();
            if (token.type != TokenType.IDENT) fail(token, "expected " + description);
            return GraphIdentifiers.requireValid(token.text, description);
        }

        /**
         * 读取非负整数，用于跳过条数、限制条数和变长路径范围。
         */
        private int parseInteger(String description) {
            Token token = next();
            if (token.type != TokenType.NUMBER || token.text.indexOf('.') >= 0)
                fail(token, "expected integer " + description);
            try {
                int value = Integer.parseInt(token.text);
                if (value < 0) fail(token, description + " must not be negative");
                return value;
            } catch (NumberFormatException error) {
                fail(token, "invalid integer " + description);
                return 0;
            }
        }

        /**
         * 如果当前 token 类型匹配则消费一个 token，否则保持当前位置不变。
         */
        private boolean accept(TokenType type) {
            if (peek().type != type) return false;
            index++;
            return true;
        }

        /**
         * 不区分大小写地消费一个关键字 token。
         */
        private boolean acceptWord(String word) {
            if (!peekWord(word)) return false;
            index++;
            return true;
        }

        /**
         * 判断当前 token 是否是不区分大小写的指定关键字。
         */
        private boolean peekWord(String word) {
            return peek().type == TokenType.WORD && word.equals(peek().upper);
        }

        /**
         * 要求当前 token 是指定关键字，否则立即报告语法错误。
         */
        private void expectWord(String word) {
            if (!acceptWord(word)) fail(peek(), "expected " + word);
        }

        /**
         * 要求当前 token 类型匹配，否则使用当前 token 位置报告错误。
         */
        private void expect(TokenType type, String message) {
            if (!accept(type)) fail(peek(), message);
        }

        /**
         * 返回并消费当前 token；END token 被重复读取时仍保持在末尾。
         */
        private Token next() {
            Token token = peek();
            if (index < tokens.size()) index++;
            return token;
        }

        /**
         * 查看当前 token，但不改变解析位置。
         */
        private Token peek() {
            return tokens.get(Math.min(index, tokens.size() - 1));
        }

        /**
         * 把内部语法失败转换成带原始文本和列号的公共异常。
         */
        private void fail(Token token, String message) {
            throw new GraphQueryParseException(source, token.position, message);
        }
    }

    /**
     * 词法 token 类型。
     */
    private enum TokenType {
        WORD, IDENT, STRING, NUMBER, LEFT_PAREN, RIGHT_PAREN, LEFT_BRACKET,
        RIGHT_BRACKET, LEFT_BRACE, RIGHT_BRACE, DASH, LEFT_ARROW, COLON, PIPE, DOT, DOT_DOT, COMMA, STAR, EQ, NE, GT,
        GE, LT, LE, END
    }

    /**
     * 词法 token。
     */
    private static final class Token {
        /**
         * token 的语法类别。
         */
        private final TokenType type;
        /**
         * token 的原始文本；字符串 token 保存去除引号后的值。
         */
        private final String text;
        /**
         * 关键字比较用的大写文本，避免重复转换。
         */
        private final String upper;
        /**
         * token 在原始查询中的 0-based 字符偏移。
         */
        private final int position;

        /**
         * 创建不可变 token，并预计算大小写无关的比较文本。
         */
        private Token(TokenType type, String text, int position) {
            this.type = type;
            this.text = text;
            this.upper = text == null ? "" : text.toUpperCase(java.util.Locale.ROOT);
            this.position = position;
        }
    }

    /**
     * 不依赖第三方库的轻量词法分析器。
     */
    private static final class Lexer {
        /**
         * 待扫描的原始查询文本。
         */
        private final String source;
        /**
         * 当前扫描字符的 0-based 下标。
         */
        private int index;

        /**
         * 创建从文本头部开始扫描的 Lexer。
         */
        private Lexer(String source) {
            this.source = source;
        }

        /**
         * 扫描全部字符并追加 END token，供递归下降解析器安全查看末尾。
         */
        private List<Token> lex() {
            List<Token> result = new ArrayList<>();
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isWhitespace(c)) {
                    index++;
                    continue;
                }
                int position = index;
                if (Character.isLetter(c) || c == '_') {
                    index++;
                    while (index < source.length() && (Character.isLetterOrDigit(source.charAt(index)) || source.charAt(index) == '_'))
                        index++;
                    String text = source.substring(position, index);
                    result.add(new Token(isKeyword(text) ? TokenType.WORD : TokenType.IDENT, text, position));
                    continue;
                }
                // 关键字冲突的结构标识符可以使用反引号引用，例如 p.`order` 或 :`RETURN`。
                if (c == '`') {
                    result.add(readIdentifier(position));
                    continue;
                }
                if (Character.isDigit(c) || (c == '-' && index + 1 < source.length() && Character.isDigit(source.charAt(index + 1)))) {
                    index++;
                    while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                    if (index < source.length() && source.charAt(index) == '.'
                        && !(index + 1 < source.length() && source.charAt(index + 1) == '.')) {
                        index++;
                        while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                    }
                    result.add(new Token(TokenType.NUMBER, source.substring(position, index), position));
                    continue;
                }
                if (c == '\'' || c == '"') {
                    result.add(readString(c, position));
                    continue;
                }
                index++;
                switch (c) {
                    case '(':
                        result.add(new Token(TokenType.LEFT_PAREN, "(", position));
                        break;
                    case ')':
                        result.add(new Token(TokenType.RIGHT_PAREN, ")", position));
                        break;
                    case '[':
                        result.add(new Token(TokenType.LEFT_BRACKET, "[", position));
                        break;
                    case ']':
                        result.add(new Token(TokenType.RIGHT_BRACKET, "]", position));
                        break;
                    case '{':
                        result.add(new Token(TokenType.LEFT_BRACE, "{", position));
                        break;
                    case '}':
                        result.add(new Token(TokenType.RIGHT_BRACE, "}", position));
                        break;
                    case ':':
                        result.add(new Token(TokenType.COLON, ":", position));
                        break;
                    case '|':
                        result.add(new Token(TokenType.PIPE, "|", position));
                        break;
                    case '.':
                        if (index < source.length() && source.charAt(index) == '.') {
                            index++;
                            result.add(new Token(TokenType.DOT_DOT, "..", position));
                        } else result.add(new Token(TokenType.DOT, ".", position));
                        break;
                    case ',':
                        result.add(new Token(TokenType.COMMA, ",", position));
                        break;
                    case '*':
                        result.add(new Token(TokenType.STAR, "*", position));
                        break;
                    case '-':
                        result.add(new Token(TokenType.DASH, "-", position));
                        break;
                    case '<':
                        if (index < source.length() && source.charAt(index) == '-') {
                            index++;
                            result.add(new Token(TokenType.LEFT_ARROW, "<-", position));
                        } else if (index < source.length() && source.charAt(index) == '>') {
                            index++;
                            result.add(new Token(TokenType.NE, "<>", position));
                        } else if (index < source.length() && source.charAt(index) == '=') {
                            index++;
                            result.add(new Token(TokenType.LE, "<=", position));
                        } else result.add(new Token(TokenType.LT, "<", position));
                        break;
                    case '>':
                        if (index < source.length() && source.charAt(index) == '=') {
                            index++;
                            result.add(new Token(TokenType.GE, ">=", position));
                        } else {
                            result.add(new Token(TokenType.GT, ">", position));
                        }
                        break;
                    case '=':
                        if (index < source.length() && source.charAt(index) == '=') {
                            index++;
                            result.add(new Token(TokenType.EQ, "==", position));
                        } else {
                            result.add(new Token(TokenType.EQ, "=", position));
                        }
                        break;
                    case '!':
                        if (index < source.length() && source.charAt(index) == '=') {
                            index++;
                            result.add(new Token(TokenType.NE, "!=", position));
                        } else throw new GraphQueryParseException(source, position, "unexpected '!'");
                        break;
                    default:
                        throw new GraphQueryParseException(source, position, "unexpected character '" + c + "'");
                }
            }
            result.add(new Token(TokenType.END, "", source.length()));
            return result;
        }

        /**
         * 读取单引号或双引号字符串，并处理反斜杠转义。
         */
        private Token readString(char quote, int position) {
            index++;
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == quote) return new Token(TokenType.STRING, value.toString(), position);
                if (c == '\\' && index < source.length()) value.append(source.charAt(index++));
                else value.append(c);
            }
            throw new GraphQueryParseException(source, position, "unterminated string literal");
        }

        /**
         * 读取反引号标识符；内容仍会在语法层经过 GraphIdentifiers 校验。
         */
        private Token readIdentifier(int position) {
            index++;
            int start = index;
            while (index < source.length() && source.charAt(index) != '`') index++;
            if (index >= source.length()) {
                throw new GraphQueryParseException(source, position, "unterminated quoted identifier");
            }
            String text = source.substring(start, index++);
            return new Token(TokenType.IDENT, text, position);
        }

        /**
         * 判断普通单词是否属于 DSL 保留关键字。
         */
        private static boolean isKeyword(String text) {
            return KEYWORDS.contains(text.toUpperCase(java.util.Locale.ROOT));
        }

        /**
         * 关键字集合只创建一次，避免每个词法 token 重建临时 List。
         */
        private static final Set<String> KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MATCH", "WHERE", "RETURN", "ORDER", "BY", "SKIP", "LIMIT", "ASC", "DESC", "GROUP", "HAVING",
            "AND", "OR", "NOT", "IN", "BETWEEN", "IS", "NULL", "AS", "COUNT", "SUM", "AVG", "MIN", "MAX", "DISTINCT",
            "CONTAINS", "STARTS", "ENDS", "WITH", "REGEX", "PATH",
            "TRUE", "FALSE")));
    }
}
