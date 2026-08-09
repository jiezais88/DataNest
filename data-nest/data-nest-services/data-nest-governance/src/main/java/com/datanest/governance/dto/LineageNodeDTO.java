package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "血缘图谱节点（表节点）")
@Data
public class LineageNodeDTO {

    @Schema(description = "唯一 ID（库名.表名 全名）", example = "ods.orders")
    private String id;

    @Schema(description = "展示名（库名.表名）")
    private String name;

    @Schema(description = "库名（表名无 schema 时为空）")
    private String database;

    @Schema(description = "血缘类型（SQL/SYNC/PYTHON）")
    private String type;

    @Schema(description = "是否当前查询的表")
    private boolean current;

    @Schema(description = "表级质量评分 0-100（未配置规则为 null，前端显示灰色「—」）")
    private Integer qualityScore;

    @Schema(description = "健康度（EXCELLENT/GOOD/WARNING/BAD，null=暂无质量数据）")
    private String healthLevel;

    @Schema(description = "库名.表名（用于血缘回填 quality_score 时按表名匹配）")
    private String tableName;

    public LineageNodeDTO(String id, String name, String database, String type, boolean current) {
        this.id = id;
        this.name = name;
        this.database = database;
        this.type = type;
        this.current = current;
    }
}
