package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.List;

@Schema(description = "用户创建请求")
public record UserCreateRequest(
        @Schema(description = "用户名（字母/数字/下划线，3~30 位）", example = "zhangsan")
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "[a-zA-Z0-9_]{3,30}", message = "用户名只能包含字母、数字、下划线") String username,
        @Schema(description = "初始密码（6~20 位）", example = "123456")
        @NotBlank @Size(min = 6, max = 20) String password,
        @Schema(description = "角色编码列表", example = "[\"DATA_ENGINEER\"]")
        @NotNull List<String> roles,
        @Schema(description = "邮箱") @Email String email,
        @Schema(description = "手机号") String phone
) {
}
