package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户选择器选项（告警接收人，仅返回已填写邮箱的用户）。
 */
@Schema(description = "用户选择器选项")
public record UserOptionDTO(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "用户名") String username,
        @Schema(description = "邮箱") String email) {
}
