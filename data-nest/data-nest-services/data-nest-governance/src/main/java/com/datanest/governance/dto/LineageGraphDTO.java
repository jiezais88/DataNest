package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "表级血缘图谱（ReactFlow 渲染数据）")
@Data
public class LineageGraphDTO {

    @Schema(description = "节点列表")
    private List<LineageNodeDTO> nodes;

    @Schema(description = "边列表")
    private List<LineageEdgeDTO> edges;

    public LineageGraphDTO(List<LineageNodeDTO> nodes, List<LineageEdgeDTO> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }
}
