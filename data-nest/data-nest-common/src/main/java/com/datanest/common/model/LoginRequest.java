package com.datanest.common.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request DTO.
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        boolean rememberMe
) {
}
