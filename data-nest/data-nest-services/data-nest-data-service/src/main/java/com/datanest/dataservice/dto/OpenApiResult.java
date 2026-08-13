package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 对外数据 API 调用结果（Sprint 10 F3）：records 行数据 + total 总记录数（分页时为 COUNT，不分页为返回行数）。
 */
@Data
@Schema(description = "对外数据 API 调用结果")
public class OpenApiResult {

    @Schema(description = "行数据（LinkedHashMap 保序）")
    private List<Map<String, Object>> records;

    @Schema(description = "总记录数（分页启用时为 COUNT 总数，关闭时为返回行数）")
    private Long total;
}
