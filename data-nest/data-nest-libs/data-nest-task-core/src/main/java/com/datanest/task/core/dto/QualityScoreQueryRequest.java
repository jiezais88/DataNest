package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "表级质量评分列表查询请求")
@Data
public class QualityScoreQueryRequest {

    @Schema(description = "页码，从 1 开始")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 10L;

    @Schema(description = "表名关键字（库名.表名 模糊匹配）")
    private String keyword;

    @Schema(description = "数据源 ID 过滤", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "健康度过滤（EXCELLENT/GOOD/WARNING/BAD）")
    private String healthLevel;
}
