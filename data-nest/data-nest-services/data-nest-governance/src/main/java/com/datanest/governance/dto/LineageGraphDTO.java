package com.datanest.governance.dto;

import lombok.Data;

import java.util.List;

/**
 * 表级血缘图谱（ReactFlow 渲染数据）。
 */
@Data
public class LineageGraphDTO {

    private List<LineageNodeDTO> nodes;

    private List<LineageEdgeDTO> edges;

    public LineageGraphDTO(List<LineageNodeDTO> nodes, List<LineageEdgeDTO> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }
}
