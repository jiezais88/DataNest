package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SQL 终端取消查询请求（Sprint 10 F1.1「停止」按钮）。
 */
@Data
@Schema(description = "SQL 终端取消查询请求")
public class SqlCancelRequest {

    @Schema(description = "发起执行时前端生成的查询标识（queryId）")
    @NotBlank(message = "queryId 不能为空")
    private String queryId;
}
