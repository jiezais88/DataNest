package com.datanest.system.dto;

import jakarta.validation.constraints.*;

import java.util.List;

public record UserCreateRequest(
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "[a-zA-Z0-9_]{3,30}", message = "用户名只能包含字母、数字、下划线") String username,
        @NotBlank @Size(min = 6, max = 20) String password,
        @NotNull List<String> roles,
        @Email String email,
        String phone
) {
}
