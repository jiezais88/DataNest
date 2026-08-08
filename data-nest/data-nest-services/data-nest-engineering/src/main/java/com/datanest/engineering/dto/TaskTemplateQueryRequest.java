package com.datanest.engineering.dto;

import lombok.Data;

/**
 * 任务模板分页查询请求（Sprint 7 F2 修订：全量 list → 分页 page，对齐平台列表页约定）。
 */
@Data
public class TaskTemplateQueryRequest {

    /** 类型过滤：SYNC / COLLECT */
    private String type;

    /** 来源过滤：BUILTIN / CUSTOM */
    private String category;

    private Integer page = 1;

    private Integer pageSize = 10;
}
