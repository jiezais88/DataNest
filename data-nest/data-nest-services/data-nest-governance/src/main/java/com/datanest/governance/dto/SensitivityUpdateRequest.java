package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 单表改级请求（Sprint 10 F5）。
 */
@Data
@Schema(description = "改级请求")
public class SensitivityUpdateRequest {

    @Schema(description = "目标敏感度：PUBLIC / INTERNAL / CONFIDENTIAL", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "目标敏感度不能为空")
    private String newLevel;
}
