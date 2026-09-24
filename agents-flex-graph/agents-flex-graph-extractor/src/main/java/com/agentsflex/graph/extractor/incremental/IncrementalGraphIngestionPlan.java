package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.GraphExtractionResult;
import com.agentsflex.graph.extractor.resolution.GraphRegisteredEntity;
import com.agentsflex.graph.mutation.GraphMutation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 增量抽取计算出的不可变、可审核执行计划。
 *
 * <p>创建计划不会写图或提交文档状态。调用方可以审核抽取问题、过期关系和 mutation，确认后再交给
 * {@link IncrementalGraphIngestionService#execute(IncrementalGraphIngestionPlan, com.agentsflex.graph.mutation.GraphWriter)}。</p>
 */
public final class IncrementalGraphIngestionPlan {
    /**
     * 计划类型。
     */
    public enum Status {
        /**
         * 内容摘要与已提交版本相同，无需调用模型或写图。
         */
        UNCHANGED,
        /**
         * 新文档或新版本已经生成待写入 mutation。
         */
        READY,
        /**
         * 撤回文档当前版本及不再被其他文档支持的关系。
         */
        RETRACTION
    }

    /**
     * 计划类型。
     */
    private final Status status;
    /**
     * 目标 Space。
     */
    private final String space;
    /**
     * 逻辑文档 ID。
     */
    private final String documentId;
    /**
     * 图写入选项。
     */
    private final GraphOptions graphOptions;
    /**
     * 生成计划时读取的旧状态。
     */
    private final GraphDocumentState previousState;
    /**
     * 写入成功后应提交的新状态；撤回计划为空。
     */
    private final GraphDocumentState nextState;
    /**
     * 新版本抽取结果；跳过和撤回计划为空。
     */
    private final GraphExtractionResult extractionResult;
    /**
     * 审核后可以执行的图变更。
     */
    private final GraphMutation mutation;
    /**
     * 相比旧版本已经消失的全部关系键。
     */
    private final Set<GraphEdgeKey> staleEdgeKeys;
    /**
     * 写成功后需要注册的实体。
     */
    private final List<GraphRegisteredEntity> entityRegistrations;

    /**
     * 包内服务专用构造器。
     */
    IncrementalGraphIngestionPlan(Status status, String space, String documentId, GraphOptions graphOptions,
                                  GraphDocumentState previousState, GraphDocumentState nextState,
                                  GraphExtractionResult extractionResult, GraphMutation mutation,
                                  Set<GraphEdgeKey> staleEdgeKeys,
                                  List<GraphRegisteredEntity> entityRegistrations) {
        if (status == null || graphOptions == null || mutation == null) {
            throw new IllegalArgumentException("status, graphOptions and mutation must not be null");
        }
        this.status = status;
        this.space = space;
        this.documentId = documentId;
        this.graphOptions = graphOptions;
        this.previousState = previousState;
        this.nextState = nextState;
        this.extractionResult = extractionResult;
        this.mutation = mutation;
        this.staleEdgeKeys = Collections.unmodifiableSet(new LinkedHashSet<>(staleEdgeKeys));
        this.entityRegistrations = Collections.unmodifiableList(new ArrayList<>(entityRegistrations));
    }

    /**
     * 从持久化字段恢复一份不可变执行计划。
     *
     * <p>该工厂主要供 {@link GraphIngestionOperationStore} 的数据库实现反序列化原始计划。恢复实现应
     * 完整保留 mutation、待提交状态和实体注册内容；否则计划指纹校验会拒绝继续执行。抽取结果只用于
     * 展示和审核，可以在存储空间受限时保存为 null。</p>
     *
     * @param status              计划类型
     * @param space               目标 Space
     * @param documentId          逻辑文档 ID
     * @param graphOptions        图写入和路由选项
     * @param previousState       生成计划时的旧状态，首次导入时为空
     * @param nextState           写入成功后提交的新状态
     * @param extractionResult    可选抽取结果
     * @param mutation            待执行图变更
     * @param staleEdgeKeys       当前计划识别出的过期关系
     * @param entityRegistrations 写图成功后保存的实体注册记录
     * @return 经过防御性复制的不可变计划
     */
    public static IncrementalGraphIngestionPlan restore(
        Status status, String space, String documentId,
        GraphOptions graphOptions,
        GraphDocumentState previousState,
        GraphDocumentState nextState,
        GraphExtractionResult extractionResult,
        GraphMutation mutation,
        Set<GraphEdgeKey> staleEdgeKeys,
        List<GraphRegisteredEntity> entityRegistrations) {
        return new IncrementalGraphIngestionPlan(status, space, documentId, graphOptions, previousState, nextState,
            extractionResult, mutation, staleEdgeKeys, entityRegistrations);
    }

    /**
     * @return 计划类型。
     */
    public Status getStatus() {
        return status;
    }

    /**
     * @return 目标 Space。
     */
    public String getSpace() {
        return space;
    }

    /**
     * @return 逻辑文档 ID。
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * @return 图操作选项。
     */
    public GraphOptions getGraphOptions() {
        return graphOptions;
    }

    /**
     * @return 旧状态；首次导入时为空。
     */
    public GraphDocumentState getPreviousState() {
        return previousState;
    }

    /**
     * @return 待提交新状态；撤回时为空。
     */
    public GraphDocumentState getNextState() {
        return nextState;
    }

    /**
     * @return 新版本抽取结果；未执行抽取时为空。
     */
    public GraphExtractionResult getExtractionResult() {
        return extractionResult;
    }

    /**
     * @return 待执行图变更。
     */
    public GraphMutation getMutation() {
        return mutation;
    }

    /**
     * @return 相比旧版本消失的全部关系，不一定都会被删除。
     */
    public Set<GraphEdgeKey> getStaleEdgeKeys() {
        return staleEdgeKeys;
    }

    /**
     * @return 图写成功后需要幂等保存的实体注册记录。
     */
    public List<GraphRegisteredEntity> getEntityRegistrations() {
        return entityRegistrations;
    }

    /**
     * @return 计划是否需要调用 GraphWriter。
     */
    public boolean isWriteRequired() {
        return !mutation.isEmpty();
    }
}
