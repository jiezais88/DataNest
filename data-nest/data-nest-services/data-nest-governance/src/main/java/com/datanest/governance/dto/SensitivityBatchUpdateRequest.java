package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量改级请求（Sprint 10 F5）：多表统一设为某级。
 */
@Data
@Schema(description = "批量改级请求")
public class SensitivityBatchUpdateRequest {

    @Schema(description = "表 ID 列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "表 ID 列表不能为空")
    private List<Long> tableIds;

    @Schema(description = "目标敏感度：PUBLIC / INTERNAL / CONFIDENTIAL", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "目标敏感度不能为空")
    private String newLevel;
}
