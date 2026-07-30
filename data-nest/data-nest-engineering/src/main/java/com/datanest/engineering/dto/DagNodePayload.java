package com.datanest.engineering.dto;

import lombok.Data;

/**
 * DAG 节点负载（请求/响应 DTO），把 config 字段显式化
 * 比 DagNode 多了 nodeType / config 解析后的内容
 */
@Data
public class DagNodePayload {
    private Long id;
    private String nodeId;
    private String nodeName;
    private String nodeType;             // SQL / SYNC
    private Double positionX;
    private Double positionY;

    /** Sprint 3 性能4：用于 generateTaskCodes 跨 dag 唯一派生（可空，新建时未持久化） */
    private Long dagId;

    /**
     * 节点配置 JSON 字符串。
     * SQL 节点: {"type":"SQL","sqlContent":"SELECT 1"}
     * SYNC 节点: {"type":"SYNC","syncJobId":123,"syncJobName":"xxx"}
     */
    private String config;
}
