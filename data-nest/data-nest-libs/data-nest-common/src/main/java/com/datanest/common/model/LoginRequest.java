package com.datanest.common.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Login request DTO.
 */
@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "用户名", example = "admin") @NotBlank String username,
        @Schema(description = "密码", example = "admin123") @NotBlank String password,
        @Schema(description = "是否记住登录（延长令牌有效期）") Boolean rememberMe
) {
    public LoginRequest {
        if (rememberMe == null) {
            rememberMe = false;
        }
    }
}
