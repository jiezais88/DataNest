package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "采集任务分页查询请求")
@Data
public class CollectTaskQueryRequest {

    @Schema(description = "关键字（按任务名称模糊匹配）")
    private String keyword;

    @Schema(description = "任务状态（NEVER_EXECUTED/RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
