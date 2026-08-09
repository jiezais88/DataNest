package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * DAG 节点负载（请求/响应 DTO），把 config 字段显式化
 * 比 DagNode 多了 nodeType / config 解析后的内容
 */
@Schema(description = "DAG 节点负载（请求/响应 DTO），config 为显式 JSON 字符串")
@Data
public class DagNodePayload {
    @Schema(description = "节点记录 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "节点标识（DAG 内唯一）")
    private String nodeId;
    @Schema(description = "节点名称")
    private String nodeName;
    @Schema(description = "节点类型（SQL/SYNC/PYTHON/CONDITION/SUB_DAG）")
    private String nodeType;             // SQL / SYNC / PYTHON / CONDITION / SUB_DAG
    @Schema(description = "画布 X 坐标")
    private Double positionX;
    @Schema(description = "画布 Y 坐标")
    private Double positionY;

    /** Sprint 3 性能4：用于 generateTaskCodes 跨 dag 唯一派生（可空，新建时未持久化） */
    @Schema(description = "所属 DAG ID（可空，新建时未持久化）", example = "1234567890123456789")
    private Long dagId;

    /**
     * 节点配置 JSON 字符串。
     * SQL 节点: {"type":"SQL","sqlContent":"SELECT 1"}
     * SYNC 节点: {"type":"SYNC","syncJobId":123,"syncJobName":"xxx"}
     */
    @Schema(description = "节点配置 JSON 字符串（按节点类型结构不同）")
    private String config;
}
