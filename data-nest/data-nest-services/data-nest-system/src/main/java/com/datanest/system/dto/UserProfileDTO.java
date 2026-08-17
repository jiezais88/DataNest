package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "个人中心 - 当前登录用户完整资料")
public record UserProfileDTO(
        @Schema(description = "用户 ID") Long userId,
        @Schema(description = "用户名") String username,
        @Schema(description = "邮箱") String email,
        @Schema(description = "手机号") String phone,
        @Schema(description = "角色编码列表") List<String> roles,
        @Schema(description = "创建时间") LocalDateTime createdAt
) {
}
