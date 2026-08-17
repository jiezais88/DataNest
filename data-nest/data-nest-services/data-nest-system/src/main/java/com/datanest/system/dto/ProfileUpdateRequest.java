package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

@Schema(description = "个人中心 - 更新当前用户资料请求（null 表示不修改，空字符串表示清空）")
public record ProfileUpdateRequest(
        @Schema(description = "邮箱")
        @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "邮箱格式不正确")
        String email,
        @Schema(description = "手机号")
        @Pattern(regexp = "^$|^\\d{6,20}$", message = "手机号格式不正确（6~20 位数字）")
        String phone
) {
}
