package com.datanest.system.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UserVO(
        Long id,
        String username,
        String email,
        String phone,
        Boolean enabled,
        List<String> roles,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
