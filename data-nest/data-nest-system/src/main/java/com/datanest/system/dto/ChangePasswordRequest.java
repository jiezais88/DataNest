package com.datanest.system.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 6, max = 20) String newPassword,
        @NotBlank @Size(min = 6, max = 20) String confirmNewPassword
) {
    @AssertTrue(message = "两次输入的新密码不一致")
    public boolean isNewPasswordMatch() {
        return newPassword != null && newPassword.equals(confirmNewPassword);
    }
}
