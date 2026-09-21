package com.shitan.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条经过校验的线性摄取流程；配置只描述节点和先后关系，不包含业务实现。
 */
public final class IngestionPipeline {

    private final String name;
    private final List<IngestionPipelineNode> orderedNodes;

    /**
     * 校验节点编号、断链、多个起点和环，然后提前计算唯一执行顺序。
     *
     * @param name  流程名称，任务会持久化这个值以便重试
     * @param nodes 尚未确认合法的节点配置
     */
    public IngestionPipeline(String name, List<IngestionPipelineNode> nodes) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("流程名称不能为空");
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("流程 " + name + " 至少需要一个节点");
        }
        this.name = name;
        this.orderedNodes = List.copyOf(validateAndOrder(name, nodes));
    }

    /**
     * 返回任务数据库中保存的流程名称。
     *
     * @return 不为空的流程名称
     */
    public String name() {
        return name;
    }

    /**
     * 返回已经从唯一入口排好顺序的只读节点列表。
     *
     * @return 实际执行顺序
     */
    public List<IngestionPipelineNode> orderedNodes() {
        return orderedNodes;
    }

    /**
     * 把 nextNodeId 形式的配置还原为线性顺序，并在任何业务副作用发生前拒绝坏配置。
     *
     * @param name  用于错误信息的流程名称
     * @param nodes 原始节点配置
     * @return 从唯一入口到末尾的节点顺序
     */
    private List<IngestionPipelineNode> validateAndOrder(
            String name,
            List<IngestionPipelineNode> nodes
    ) {
        Map<String, IngestionPipelineNode> byId = new LinkedHashMap<>();
        for (IngestionPipelineNode node : nodes) {
            if (node == null || node.id() == null || node.id().isBlank() || node.type() == null) {
                throw new IllegalArgumentException("流程 " + name + " 存在不完整节点");
            }
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("流程 " + name + " 的节点编号重复：" + node.id());
            }
        }

        Set<String> referencedIds = new HashSet<>();
        for (IngestionPipelineNode node : nodes) {
            String nextId = node.nextNodeId();
            if (nextId != null && !byId.containsKey(nextId)) {
                throw new IllegalArgumentException(
                        "流程 " + name + " 在节点 " + node.id() + " 后断链：" + nextId
                );
            }
            if (nextId != null) {
                referencedIds.add(nextId);
            }
        }

        detectCycle(name, byId);
        List<IngestionPipelineNode> starts = nodes.stream()
                .filter(node -> !referencedIds.contains(node.id()))
                .toList();
        if (starts.size() != 1) {
            throw new IllegalArgumentException(
                    "流程 " + name + " 必须且只能有一个起点，实际为：" + starts.size()
            );
        }

        List<IngestionPipelineNode> ordered = new ArrayList<>();
        IngestionPipelineNode current = starts.get(0);
        while (current != null) {
            ordered.add(current);
            current = current.nextNodeId() == null ? null : byId.get(current.nextNodeId());
        }
        if (ordered.size() != nodes.size()) {
            throw new IllegalArgumentException("流程 " + name + " 存在无法从入口到达的节点");
        }
        return ordered;
    }

    /**
     * 从每个节点沿 next 指针行走；同一路径再次遇到节点就说明流程会无限循环。
     *
     * @param name 用于错误信息的流程名称
     * @param byId 以节点编号组织的配置
     */
    private void detectCycle(String name, Map<String, IngestionPipelineNode> byId) {
        Map<String, Integer> states = new HashMap<>();
        for (String nodeId : byId.keySet()) {
            detectCycleFrom(name, nodeId, byId, states);
        }
    }

    /**
     * 用 1 表示“正在当前路径中”、2 表示“已经检查完”，从而区分真正的环和正常汇合。
     *
     * @param name   流程名称
     * @param nodeId 当前检查的节点
     * @param byId   全部节点配置
     * @param states 每个节点的遍历状态
     */
    private void detectCycleFrom(
            String name,
            String nodeId,
            Map<String, IngestionPipelineNode> byId,
            Map<String, Integer> states
    ) {
        Integer state = states.get(nodeId);
        if (Integer.valueOf(1).equals(state)) {
            throw new IllegalArgumentException("流程 " + name + " 存在环，经过节点：" + nodeId);
        }
        if (Integer.valueOf(2).equals(state)) {
            return;
        }

        states.put(nodeId, 1);
        String nextId = byId.get(nodeId).nextNodeId();
        if (nextId != null) {
            detectCycleFrom(name, nextId, byId, states);
        }
        states.put(nodeId, 2);
    }
}
