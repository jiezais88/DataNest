package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "血缘图谱边（source → target）")
@Data
public class LineageEdgeDTO {

    @Schema(description = "源节点 ID（库名.表名）", example = "ods.orders")
    private String source;

    @Schema(description = "目标节点 ID（库名.表名）", example = "dwd.order_detail")
    private String target;

    @Schema(description = "血缘类型（SQL/SYNC/PYTHON）")
    private String lineageType;

    public LineageEdgeDTO(String source, String target, String lineageType) {
        this.source = source;
        this.target = target;
        this.lineageType = lineageType;
    }
}
