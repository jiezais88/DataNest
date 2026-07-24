package com.datanest.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserCreateRequest(
        @NotBlank @Size(min = 3, max = 30) String username,
        @NotBlank @Size(min = 6, max = 20) String password,
        @NotNull List<String> roles,
        @Email String email,
        String phone
) {
}
