package com.datanest.engineering.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.entity.DagEdge;
import com.datanest.engineering.entity.DagNode;
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
     * Sprint 3 API 测试发现 bug：原实现把所有入度=0 的节点当 root，
     * 导致"完全没边的孤立节点"被当成有效 root 通过校验。
     * 编排 DAG 必须是连通图。修正后判定：
     *   - 真正连通图：所有节点都从某个 root 出发可达
     *   - 边数 + 1 == 节点数 → 唯一连通
     *   - 边数 + 1 < 节点数 → 必有孤立节点
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

        // 邻接表 + 反向邻接表
        Map<String, Set<String>> forward = new HashMap<>();
        Map<String, Set<String>> backward = new HashMap<>();
        for (DagNode node : nodes) {
            forward.putIfAbsent(node.getNodeId(), new HashSet<>());
            backward.putIfAbsent(node.getNodeId(), new HashSet<>());
        }
        int validEdgeCount = 0;
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
                backward.computeIfAbsent(edge.getTargetNodeId(), k -> new HashSet<>())
                        .add(edge.getSourceNodeId());
                validEdgeCount++;
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

        // 2. 找出"无任何边相连"的节点（多节点时才是孤立）
        //    Sprint 3 修正：
        //    - 单节点（没有边）→ 合法（最简单的 1 步 SQL/SYNC 任务）
        //    - 多节点 + 有节点完全孤立（既无入边也无出边）→ 拒绝
        Set<String> noEdgeNodes = new LinkedHashSet<>();
        for (String id : forward.keySet()) {
            if (forward.get(id).isEmpty() && backward.get(id).isEmpty()) {
                noEdgeNodes.add(id);
            }
        }
        if (nodeById.size() > 1 && !noEdgeNodes.isEmpty()) {
            throw new BusinessException(ErrorCode.DAG_ISOLATED_NODE,
                    "DAG 存在孤立节点（无任何边相连）: " + String.join(",", noEdgeNodes));
        }

        // 3. 找所有入度为 0 的起点
        List<String> roots = new ArrayList<>();
        for (String id : forward.keySet()) {
            if (backward.getOrDefault(id, Collections.emptySet()).isEmpty()) {
                roots.add(id);
            }
        }

        // 4. 从每个起点做正向可达性
        Set<String> reachable = new HashSet<>();
        for (String root : roots) {
            bfsForward(root, forward, reachable);
        }

        // 5. 边数验证：连通 DAG 必须有 (节点数 - 1) 条边
        //    简化：edgeCount + 1 < nodeCount → 必有孤立（多节点 + 边数不够）
        if (nodeById.size() > 1 && validEdgeCount + 1 < nodeById.size()) {
            Set<String> isolated = new LinkedHashSet<>(nodeById.keySet());
            isolated.removeAll(reachable);
            throw new BusinessException(ErrorCode.DAG_ISOLATED_NODE,
                    "DAG 存在孤立节点（边数 " + validEdgeCount + " + 1 < 节点数 " + nodeById.size() + "）: "
                            + String.join(",", isolated));
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
