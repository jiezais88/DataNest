package com.datanest.common.scheduler;

/**
 * PowerJob 工作流 DAG 边 DTO（对应 powerjob-common PEWorkflowDAG.Edge 的 from/to）。
 */
public class PJEdge {

    /** 起点节点 ID */
    private Long from;
    /** 终点节点 ID */
    private Long to;

    public PJEdge() {
    }

    public PJEdge(Long from, Long to) {
        this.from = from;
        this.to = to;
    }

    public Long getFrom() {
        return from;
    }

    public void setFrom(Long from) {
        this.from = from;
    }

    public Long getTo() {
        return to;
    }

    public void setTo(Long to) {
        this.to = to;
    }
}
