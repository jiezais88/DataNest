package com.datanest.governance.dto;

import lombok.Data;

@Data
public class CollectTaskQueryRequest {

    private String keyword;

    private String status;

    private Long datasourceId;

    private Integer page = 1;

    private Integer pageSize = 10;
}
