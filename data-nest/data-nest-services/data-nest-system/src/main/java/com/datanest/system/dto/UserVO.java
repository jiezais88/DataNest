package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "用户视图对象")
public record UserVO(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "用户名") String username,
        @Schema(description = "邮箱") String email,
        @Schema(description = "手机号") String phone,
        @Schema(description = "是否启用") Boolean enabled,
        @Schema(description = "角色编码列表") List<String> roles,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt,
        @Schema(description = "创建人 ID") Long createdBy,
        @Schema(description = "创建人用户名") String createdByName,
        @Schema(description = "更新人 ID") Long updatedBy,
        @Schema(description = "更新人用户名") String updatedByName
) {
}
