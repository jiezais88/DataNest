package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量任务分页查询请求")
@Data
public class QualityJobQueryRequest {

    @Schema(description = "页码，从 1 开始")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 10L;

    @Schema(description = "关键字（名称/描述模糊匹配）")
    private String keyword;

    @Schema(description = "启用状态过滤（1 启用，0 停用）")
    private Integer enabled;

    @Schema(description = "是否开定时调度过滤（1 开，0 关）")
    private Integer scheduledEnabled;
}
