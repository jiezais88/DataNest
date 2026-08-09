package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "重置密码请求（管理员操作）")
public record ResetPasswordRequest(
        @Schema(description = "新密码（6~20 位）") @NotBlank @Size(min = 6, max = 20) String newPassword
) {
}
