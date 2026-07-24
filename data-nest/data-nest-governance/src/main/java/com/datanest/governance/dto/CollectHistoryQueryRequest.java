package com.datanest.governance.dto;

import lombok.Data;

@Data
public class CollectHistoryQueryRequest {

    private Long taskId;

    private String status;

    private Integer page = 1;

    private Integer pageSize = 10;
}
