package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务模板分页查询请求（Sprint 7 F2 修订：全量 list → 分页 page，对齐平台列表页约定）。
 */
@Schema(description = "任务模板分页查询请求")
@Data
public class TaskTemplateQueryRequest {

    /** 类型过滤：SYNC / COLLECT */
    @Schema(description = "模板类型过滤（SYNC/COLLECT）")
    private String type;

    /** 来源过滤：BUILTIN / CUSTOM */
    @Schema(description = "模板来源过滤（BUILTIN/CUSTOM）")
    private String category;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
