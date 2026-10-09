/**
 * 跨文档、跨批次实体身份注册能力。
 *
 * <p>本包保存和查询已经确认的节点 ID、规范名称、别名及属性快照。实体归一策略位于
 * {@code com.agentsflex.graph.extractor.resolution} 包；入图服务在图写入成功后提交注册记录。
 * 注册表实现负责身份持久化，不承担模型抽取、候选审核或图写入。</p>
 */
package com.agentsflex.graph.extractor.registry;
