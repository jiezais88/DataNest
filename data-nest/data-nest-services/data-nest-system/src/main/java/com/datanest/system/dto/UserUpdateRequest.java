package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

import java.util.List;

@Schema(description = "用户更新请求（空字段表示不修改）")
public record UserUpdateRequest(
        @Schema(description = "新密码（6~20 位，不传则不修改）") String password,
        @Schema(description = "角色编码列表") List<String> roles,
        @Schema(description = "邮箱") @Email String email,
        @Schema(description = "手机号") String phone
) {
}
