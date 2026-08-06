package com.datanest.governance.dto;

import lombok.Data;

/**
 * 血缘图谱边（source → target）。
 */
@Data
public class LineageEdgeDTO {

    private String source;

    private String target;

    /** SQL / SYNC / PYTHON */
    private String lineageType;

    public LineageEdgeDTO(String source, String target, String lineageType) {
        this.source = source;
        this.target = target;
        this.lineageType = lineageType;
    }
}
