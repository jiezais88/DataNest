package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "DAG 边负载（请求/响应 DTO）")
@Data
public class DagEdgePayload {
    @Schema(description = "边记录 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "边标识（DAG 内唯一）")
    private String edgeId;
    @Schema(description = "源节点标识")
    private String sourceNodeId;
    @Schema(description = "目标节点标识")
    private String targetNodeId;
}
