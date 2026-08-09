package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量规则分页查询请求")
@Data
public class QualityRuleQueryRequest {

    @Schema(description = "页码，从 1 开始")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 10L;

    @Schema(description = "关键字（规则名称模糊匹配）")
    private String keyword;

    @Schema(description = "规则类型过滤（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    private String type;

    @Schema(description = "启用状态过滤（1 启用，0 停用）")
    private Integer enabled;

    @Schema(description = "所属任务过滤（经 quality_job_rule 关联表过滤）", example = "1234567890123456789")
    private Long jobId;

    @Schema(description = "目标表 ID 过滤", example = "1234567890123456789")
    private Long tableId;
}
