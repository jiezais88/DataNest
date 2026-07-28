package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class FieldTypeStandardDTO {

    private Long id;

    private String name;

    private String category;

    private List<String> allowedTypes;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
