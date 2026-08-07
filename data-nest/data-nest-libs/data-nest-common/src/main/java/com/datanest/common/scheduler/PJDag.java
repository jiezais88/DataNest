package com.datanest.common.scheduler;

import java.util.List;

/**
 * PowerJob 工作流 DAG DTO（对应 powerjob-common PEWorkflowDAG 的点线表示法）。
 * 序列化 JSON 结构：{"nodes":[{nodeId,nodeName,nodeType,jobId,nodeParams,skipWhenFailed}...],"edges":[{from,to}...]}。
 */
public class PJDag {

    /** DAG 节点列表 */
    private List<PJNode> nodes;
    /** DAG 边列表 */
    private List<PJEdge> edges;

    public PJDag() {
    }

    public PJDag(List<PJNode> nodes, List<PJEdge> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public List<PJNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<PJNode> nodes) {
        this.nodes = nodes;
    }

    public List<PJEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<PJEdge> edges) {
        this.edges = edges;
    }
}
