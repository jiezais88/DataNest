package com.datanest.system.dto;

import jakarta.validation.constraints.Email;

import java.util.List;

public record UserUpdateRequest(
        String password,
        List<String> roles,
        @Email String email,
        String phone
) {
}
