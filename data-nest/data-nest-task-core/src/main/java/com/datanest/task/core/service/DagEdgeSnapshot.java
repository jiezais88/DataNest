package com.datanest.task.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.datanest.task.core.entity.DagEdge;
import com.datanest.task.core.mapper.DagEdgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * DAG 执行实例的边快照工具。
 * Sprint 4 下沉到 task-core，供 engineering / worker 共用。
 * trigger / CRON 自动补单创建 dag_execution 行时，把该 DAG 当前 dag_edge 列表
 * 序列化为 JSON（[{ "source": "<nodeId>", "target": "<nodeId>" }, ...]，无边时 []）
 * 写入 edge_snapshot 列，历史视图（run-view）据此渲染边，
 * 避免用户删除节点后历史实例的连线丢失。
 * <p>
 * 序列化失败不阻断 trigger，降级为 null（前端回退用当前 dag_edge 渲染）并记 warn。
 */
public final class DagEdgeSnapshot {

    private static final Logger logger = LoggerFactory.getLogger(DagEdgeSnapshot.class);

    private DagEdgeSnapshot() {
    }

    /**
     * 读取 DAG 当前 dag_edge 列表并序列化为快照 JSON。
     *
     * @return 快照 JSON 字符串；序列化失败时返回 null
     */
    public static String capture(DagEdgeMapper dagEdgeMapper, Long dagId) {
        try {
            List<DagEdge> edges = dagEdgeMapper.selectByDagId(dagId);
            JSONArray arr = new JSONArray(edges.size());
            for (DagEdge edge : edges) {
                JSONObject obj = new JSONObject();
                obj.put("source", edge.getSourceNodeId());
                obj.put("target", edge.getTargetNodeId());
                arr.add(obj);
            }
            return arr.toJSONString();
        } catch (Exception e) {
            logger.warn("序列化边快照失败，edge_snapshot 降级为 null: dagId={}", dagId, e);
            return null;
        }
    }
}
