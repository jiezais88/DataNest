package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 内部表 API 开白请求（Sprint 10 F5，T6）：仅超管，仅 INTERNAL 表。
 */
@Data
@Schema(description = "API 开白请求")
public class SensitivityExemptRequest {

    @Schema(description = "是否开白：1 开白 / 0 取消开白", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "开白标记不能为空")
    private Integer apiExempted;
}
