package com.datanest.common.scheduler;

/**
 * PowerJob 工作流 DAG 节点 DTO（对应 powerjob-common PEWorkflowDAG.Node 的子集字段）。
 * nodeType 传数字：1=JOB 任务节点、2=DECISION 判断节点、3=NESTED_WORKFLOW 内嵌工作流。
 */
public class PJNode {

    /** 节点 ID（须为 server 侧 workflow_node_info 已存在的节点记录 ID） */
    private Long nodeId;
    /** 节点名称（别名） */
    private String nodeName;
    /** 节点类型：1=JOB、2=DECISION、3=NESTED_WORKFLOW */
    private Integer nodeType;
    /** 任务 ID（NESTED_WORKFLOW 节点时为被嵌套的工作流 ID） */
    private Long jobId;
    /** 节点参数（DECISION 节点为 JavaScript 代码） */
    private String nodeParams;
    /** 失败时是否跳过 */
    private Boolean skipWhenFailed;

    public PJNode() {
    }

    public PJNode(Long nodeId, String nodeName, Integer nodeType, Long jobId, String nodeParams, Boolean skipWhenFailed) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.nodeType = nodeType;
        this.jobId = jobId;
        this.nodeParams = nodeParams;
        this.skipWhenFailed = skipWhenFailed;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Integer getNodeType() {
        return nodeType;
    }

    public void setNodeType(Integer nodeType) {
        this.nodeType = nodeType;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getNodeParams() {
        return nodeParams;
    }

    public void setNodeParams(String nodeParams) {
        this.nodeParams = nodeParams;
    }

    public Boolean getSkipWhenFailed() {
        return skipWhenFailed;
    }

    public void setSkipWhenFailed(Boolean skipWhenFailed) {
        this.skipWhenFailed = skipWhenFailed;
    }
}
