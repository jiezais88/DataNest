package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "修改密码请求（用户自主）")
public record ChangePasswordRequest(
        @Schema(description = "原密码") @NotBlank String oldPassword,
        @Schema(description = "新密码（6~20 位）") @NotBlank @Size(min = 6, max = 20) String newPassword,
        @Schema(description = "确认新密码") @NotBlank @Size(min = 6, max = 20) String confirmNewPassword
) {
    @AssertTrue(message = "两次输入的新密码不一致")
    public boolean isNewPasswordMatch() {
        return newPassword != null && newPassword.equals(confirmNewPassword);
    }
}
