package com.datanest.task.core.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.entity.DagEdge;
import com.datanest.task.core.entity.DagNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DAG 拓扑校验服务
 * 算法：DFS 三色标记检测环 + 反向图求可达性，识别孤立节点（不与任何起点连通）
 */
@Service
public class DagTopologyService {

    private static final Logger logger = LoggerFactory.getLogger(DagTopologyService.class);

    // 节点颜色：0=未访问, 1=访问中, 2=已完成
    private static final int WHITE = 0;
    private static final int GRAY = 1;
    private static final int BLACK = 2;

    /**
     * 校验 DAG 无环 + 无孤立节点 + 拓扑排序
     * 任何失败抛 BusinessException
     *
     * @return 拓扑序后的节点列表（起点在前）
     */
    public List<DagNode> validateAndSort(List<DagNode> nodes, List<DagEdge> edges) {
        if (nodes == null || nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 节点为空");
        }
        Map<String, DagNode> nodeById = new HashMap<>();
        for (DagNode node : nodes) {
            nodeById.put(node.getNodeId(), node);
        }

        // 邻接表（正向：source -> [target]）
        Map<String, Set<String>> forward = new HashMap<>();
        for (DagNode node : nodes) {
            forward.putIfAbsent(node.getNodeId(), new HashSet<>());
        }
        if (edges != null) {
            for (DagEdge edge : edges) {
                if (!nodeById.containsKey(edge.getSourceNodeId())
                        || !nodeById.containsKey(edge.getTargetNodeId())) {
                    logger.warn("DAG 边引用了不存在的节点: edgeId={}, source={}, target={}",
                            edge.getEdgeId(), edge.getSourceNodeId(), edge.getTargetNodeId());
                    continue;
                }
                forward.computeIfAbsent(edge.getSourceNodeId(), k -> new HashSet<>())
                        .add(edge.getTargetNodeId());
            }
        }

        // 1. DFS 三色检测环 + 拓扑排序
        Map<String, Integer> color = new HashMap<>();
        for (String id : forward.keySet()) color.put(id, WHITE);
        List<String> topoOrder = new ArrayList<>();
        for (String start : forward.keySet()) {
            if (color.get(start) == WHITE) {
                dfsDetectCycle(start, forward, color, topoOrder, new ArrayList<>());
            }
        }
        Collections.reverse(topoOrder);

        // 2. 找所有入度为 0 的起点
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : forward.keySet()) inDegree.put(id, 0);
        for (Map.Entry<String, Set<String>> e : forward.entrySet()) {
            for (String t : e.getValue()) inDegree.merge(t, 1, Integer::sum);
        }
        List<String> roots = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) roots.add(e.getKey());
        }

        // 3. 从每个起点做正向可达性
        Set<String> reachable = new HashSet<>();
        for (String root : roots) {
            bfsForward(root, forward, reachable);
        }

        // 4. 孤立节点 = 所有节点 - 可达
        Set<String> isolated = new LinkedHashSet<>(nodeById.keySet());
        isolated.removeAll(reachable);
        if (!isolated.isEmpty()) {
            throw new BusinessException(ErrorCode.DAG_ISOLATED_NODE,
                    "DAG 存在孤立节点（不与任何起点连通）: " + String.join(",", isolated));
        }

        // 5. 拓扑序返回
        List<DagNode> sorted = new ArrayList<>();
        for (String id : topoOrder) {
            DagNode n = nodeById.get(id);
            if (n != null) sorted.add(n);
        }
        return sorted;
    }

    private void dfsDetectCycle(String node, Map<String, Set<String>> forward,
                                Map<String, Integer> color, List<String> topoOrder, List<String> path) {
        color.put(node, GRAY);
        path.add(node);
        for (String next : forward.getOrDefault(node, Collections.emptySet())) {
            if (color.get(next) == GRAY) {
                throw new BusinessException(ErrorCode.DAG_CYCLE_DETECTED,
                        "DAG 存在循环依赖: " + String.join(" -> ", path) + " -> " + next);
            }
            if (color.get(next) == WHITE) {
                dfsDetectCycle(next, forward, color, topoOrder, path);
            }
        }
        color.put(node, BLACK);
        topoOrder.add(node);
        path.remove(path.size() - 1);
    }

    private void bfsForward(String start, Map<String, Set<String>> forward, Set<String> reachable) {
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        reachable.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : forward.getOrDefault(cur, Collections.emptySet())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
    }
}
