package com.datanest.alert.api.dto;

import lombok.Data;

import java.util.List;

/**
 * DAG 执行完成通知请求。
 */
@Data
public class DagFinishedRequest {

    /** DAG 执行实例信息 */
    private DagExecutionInfo execution;

    /** 节点执行信息列表 */
    private List<NodeExecutionInfo> nodes;
}
