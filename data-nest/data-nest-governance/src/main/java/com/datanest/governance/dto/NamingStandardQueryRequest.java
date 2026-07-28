package com.datanest.governance.dto;

import lombok.Data;

@Data
public class NamingStandardQueryRequest {

    private String keyword;

    private String appliesTo;

    private Integer enabled;

    private Integer page = 1;

    private Integer pageSize = 10;
}
