package com.datanest.governance.dto;

import lombok.Data;

@Data
public class FieldTypeStandardQueryRequest {

    private String keyword;

    private String category;

    private Integer page = 1;

    private Integer pageSize = 10;
}
