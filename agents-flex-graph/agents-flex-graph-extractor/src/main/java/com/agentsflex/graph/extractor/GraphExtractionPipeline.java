package com.agentsflex.graph.extractor;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.splitter.SimpleDocumentSplitter;
import com.agentsflex.graph.extractor.mapping.GraphCandidateMutationMapper;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolutionResult;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolver;
import com.agentsflex.graph.extractor.resolution.NameAliasGraphEntityResolver;
import com.agentsflex.graph.extractor.validation.GraphCandidateValidationResult;
import com.agentsflex.graph.extractor.validation.GraphCandidateValidator;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.schema.GraphSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 编排文档分段、局部抽取、Schema 校验、实体归一和 GraphMutation 映射。
 *
 * <p>该类没有可变的单次请求状态，可以作为应用单例并发复用；前提是注入的 GraphExtractor、
 * DocumentSplitter 和 GraphEntityResolver 实现本身支持并发调用。</p>
 */
public final class GraphExtractionPipeline {
    /**
     * 局部候选知识抽取器。
     */
    private final GraphExtractor extractor;
    /**
     * 文档分段器。
     */
    private final DocumentSplitter splitter;
    /**
     * Schema 和质量校验器。
     */
    private final GraphCandidateValidator validator;
    /**
     * 跨分段实体解析器。
     */
    private final GraphEntityResolver resolver;
    /**
     * 候选知识到 GraphMutation 的映射器。
     */
    private final GraphCandidateMutationMapper mutationMapper;

    /**
     * 使用 1200 字符、200 字符重叠及默认校验、归一和映射策略创建流水线。
     *
     * @param extractor 实际执行单个 Chunk 知识抽取的实现，不能为空
     */
    public GraphExtractionPipeline(GraphExtractor extractor) {
        this(extractor, new SimpleDocumentSplitter(1200, 200), new SchemaGraphCandidateValidator(),
            new NameAliasGraphEntityResolver(), new GraphCandidateMutationMapper());
    }

    /**
     * 使用自定义组件创建知识抽取流水线。
     *
     * @param extractor      局部候选知识抽取器
     * @param splitter       完整文档分段器；调用 {@link #extractChunks(List, GraphSchema)} 时不会使用
     * @param validator      Schema 和质量校验器
     * @param resolver       跨分段实体归一器
     * @param mutationMapper 候选结果到 GraphMutation 的映射器
     */
    public GraphExtractionPipeline(GraphExtractor extractor, DocumentSplitter splitter,
                                   GraphCandidateValidator validator, GraphEntityResolver resolver,
                                   GraphCandidateMutationMapper mutationMapper) {
        if (extractor == null || splitter == null || validator == null || resolver == null || mutationMapper == null) {
            throw new IllegalArgumentException("pipeline components must not be null");
        }
        this.extractor = extractor;
        this.splitter = splitter;
        this.validator = validator;
        this.resolver = resolver;
        this.mutationMapper = mutationMapper;
    }

    /**
     * 使用默认质量选项抽取完整文档。
     *
     * @param document 尚未分段的完整文档
     * @param schema   本次抽取允许使用的图 Schema
     * @return 不会自动写库的可审核抽取结果
     */
    public GraphExtractionResult extract(Document document, GraphSchema schema) {
        return extract(document, schema, GraphExtractionOptions.DEFAULT);
    }

    /**
     * 抽取完整文档，但不写入数据库。
     *
     * @param document 已由 DocumentExtractor 解析的文档
     * @param schema   允许抽取的节点、关系和属性
     * @param options  质量、断言和上下文选项
     * @return 可审核的候选结果与 GraphMutation
     */
    public GraphExtractionResult extract(Document document, GraphSchema schema, GraphExtractionOptions options) {
        if (document == null || document.getContent() == null || document.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("document content must not be blank");
        }
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        GraphExtractionOptions resolvedOptions = options == null ? GraphExtractionOptions.DEFAULT : options;
        List<Document> chunks = splitter.split(document);
        if (chunks == null || chunks.isEmpty()) chunks = Collections.singletonList(document);
        String documentId = textId(document.getId());
        return processChunks(chunks, schema, resolvedOptions, documentId);
    }

    /**
     * 使用默认质量选项直接抽取调用方已经切分好的文档列表。
     *
     * <p>该入口不会再次调用 {@link DocumentSplitter}，适合复用 Agents-Flex 文档解析与分段链路的
     * 既有结果。每个 Document 的 ID 会作为 Chunk ID；未提供 ID 时按列表顺序生成稳定回退值。</p>
     *
     * @param chunks 已经按语义或长度切分的非空 Chunk 列表
     * @param schema 本次抽取允许使用的图 Schema
     * @return 不会自动写库的可审核抽取结果
     */
    public GraphExtractionResult extractChunks(List<Document> chunks, GraphSchema schema) {
        return extractChunks(chunks, "", schema, GraphExtractionOptions.DEFAULT);
    }

    /**
     * 使用指定质量选项直接抽取调用方已经切分好的文档列表。
     *
     * <p>该入口没有完整父文档对象，因此证据的 documentId 为空；调用方仍可通过 Chunk ID 和
     * Document metadata 携带自己的来源标识。为避免 mentionId 作用域碰撞，重复 Chunk ID 会在
     * 调用模型前被拒绝。</p>
     *
     * @param chunks  已经切分的非空 Chunk 列表
     * @param schema  本次抽取允许使用的图 Schema
     * @param options 质量阈值、断言策略、上下文长度和错误处理策略；为 {@code null} 时使用默认值
     * @return 不会自动写库的可审核抽取结果
     */
    public GraphExtractionResult extractChunks(List<Document> chunks, GraphSchema schema,
                                               GraphExtractionOptions options) {
        return extractChunks(chunks, "", schema, options);
    }

    /**
     * 使用默认质量选项抽取已有分段，并显式保留共同的父文档 ID。
     *
     * @param chunks     已经切分的非空 Chunk 列表
     * @param documentId 所有 Chunk 所属的父文档 ID；允许为空字符串
     * @param schema     本次抽取允许使用的图 Schema
     * @return 不会自动写库的可审核抽取结果
     */
    public GraphExtractionResult extractChunks(List<Document> chunks, String documentId, GraphSchema schema) {
        return extractChunks(chunks, documentId, schema, GraphExtractionOptions.DEFAULT);
    }

    /**
     * 使用指定质量选项抽取已有分段，并把共同父文档 ID 写入每条 GraphEvidence。
     *
     * <p>documentId 不参与 Chunk 唯一性判断；每个 Chunk 仍优先使用自己的 Document ID，缺失时
     * 才回退为“父文档 ID + 分段序号”。</p>
     *
     * @param chunks     已经切分的非空 Chunk 列表
     * @param documentId 所有 Chunk 所属的父文档 ID；为 {@code null} 时按空字符串处理
     * @param schema     本次抽取允许使用的图 Schema
     * @param options    质量、上下文和容错选项；为 {@code null} 时使用默认值
     * @return 不会自动写库的可审核抽取结果
     */
    public GraphExtractionResult extractChunks(List<Document> chunks, String documentId, GraphSchema schema,
                                               GraphExtractionOptions options) {
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("chunks must not be empty");
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        GraphExtractionOptions resolvedOptions = options == null ? GraphExtractionOptions.DEFAULT : options;
        return processChunks(new ArrayList<>(chunks), schema, resolvedOptions, textId(documentId));
    }

    /**
     * 执行所有入口共享的逐 Chunk 抽取、校验、归一和映射流程。
     *
     * <p>先一次性验证全部 Chunk 和 ID，再开始调用模型，避免处理到一半才发现重复 ID 而返回
     * 不完整副作用。虽然流水线本身不会写库，但自定义 GraphExtractor 仍可能访问外部服务。</p>
     */
    private GraphExtractionResult processChunks(List<Document> chunks, GraphSchema schema,
                                                GraphExtractionOptions options, String documentId) {
        List<String> chunkIds = resolveChunkIds(chunks, documentId);
        List<GraphEntityCandidate> allEntities = new ArrayList<>();
        List<GraphRelationCandidate> allRelations = new ArrayList<>();
        List<GraphEntityCandidate> entities = new ArrayList<>();
        List<GraphRelationCandidate> relations = new ArrayList<>();
        List<GraphExtractionIssue> issues = new ArrayList<>();
        List<String> rawResponses = new ArrayList<>();
        String previous = "";
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String chunkId = chunkIds.get(i);
            GraphExtractionRequest request = GraphExtractionRequest.builder(chunk.getContent(), schema, chunkId)
                .documentId(documentId).context(tail(previous, options.getContextCharacters()))
                .metadata(chunk.getMetadataMap()).options(options).build();
            GraphCandidateResult extracted;
            try {
                extracted = extractor.extract(request);
                if (extracted == null) throw new GraphExtractionException("GraphExtractor returned null");
            } catch (RuntimeException exception) {
                if (options.isFailOnChunkError()) throw exception;
                issues.add(new GraphExtractionIssue("CHUNK_EXTRACTION_FAILED",
                    GraphExtractionIssue.Severity.ERROR, chunkId,
                    exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage()));
                rawResponses.add("");
                previous = chunk.getContent();
                continue;
            }
            allEntities.addAll(extracted.getEntities());
            allRelations.addAll(extracted.getRelations());
            rawResponses.add(extracted.getRawResponse());
            GraphCandidateResult accepted;
            try {
                GraphCandidateValidationResult validation = validator.validate(extracted, request);
                if (validation == null || validation.getAccepted() == null) {
                    throw new GraphExtractionException("GraphCandidateValidator returned null");
                }
                accepted = validation.getAccepted();
            } catch (RuntimeException exception) {
                if (options.isFailOnChunkError()) throw exception;
                // 解析器已经发现的问题仍需保留，随后追加校验阶段自身的失败原因。
                issues.addAll(extracted.getIssues());
                issues.add(new GraphExtractionIssue("CHUNK_VALIDATION_FAILED",
                    GraphExtractionIssue.Severity.ERROR, chunkId,
                    exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage()));
                previous = chunk.getContent();
                continue;
            }
            entities.addAll(accepted.getEntities());
            relations.addAll(accepted.getRelations());
            issues.addAll(accepted.getIssues());
            previous = chunk.getContent();
        }
        GraphEntityResolutionResult resolution = resolver.resolve(entities);
        GraphMutation mutation = mutationMapper.map(resolution, relations);
        return new GraphExtractionResult(allEntities, allRelations, entities, relations,
            resolution, mutation, issues, rawResponses);
    }

    /**
     * 解析并验证全部 Chunk ID。
     *
     * <p>显式 ID 优先；缺失 ID 时，完整文档入口使用“文档 ID + 序号”，预分段入口使用
     * “chunk + 序号”。所有最终 ID 都必须唯一，因为它们会参与候选键和证据定位。</p>
     */
    private static List<String> resolveChunkIds(List<Document> chunks, String documentId) {
        List<String> result = new ArrayList<>(chunks.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            if (chunk == null || chunk.getContent() == null || chunk.getContent().trim().isEmpty()) {
                throw new IllegalArgumentException("chunk content must not be blank at index " + i);
            }
            String chunkId = textId(chunk.getId());
            if (chunkId.isEmpty()) {
                chunkId = documentId.isEmpty() ? "chunk-" + i : documentId + "#chunk-" + i;
            }
            if (!seen.add(chunkId)) throw new IllegalArgumentException("duplicate chunk id: " + chunkId);
            result.add(chunkId);
        }
        return result;
    }

    /**
     * 把可选 Document ID 转换为已裁剪字符串，空值统一返回空字符串。
     */
    private static String textId(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 返回字符串尾部指定字符数，用作下一分段的只读消歧上下文。
     *
     * <p>上下文只传递给提示词，不会作为当前分段证据，也不会被直接写入图数据库。</p>
     */
    private static String tail(String value, int max) {
        if (value == null || value.isEmpty() || max == 0) return "";
        return value.length() <= max ? value : value.substring(value.length() - max);
    }
}
