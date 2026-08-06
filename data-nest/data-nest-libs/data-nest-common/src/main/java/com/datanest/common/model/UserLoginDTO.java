package com.datanest.common.model;

import java.util.List;

/**
 * User login info returned by system-service for gateway Feign call.
 */
public record UserLoginDTO(
        Long userId,
        String username,
        String password,
        boolean enabled,
        List<String> roles,
        List<String> permissions
) {
}
