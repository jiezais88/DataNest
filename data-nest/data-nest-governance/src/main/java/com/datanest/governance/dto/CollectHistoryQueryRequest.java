package com.datanest.governance.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectHistoryQueryRequest {

    private Long taskId;

    private String status;

    @NotNull(message = "开始时间起不能为空")
    private LocalDateTime startTimeFrom;

    @NotNull(message = "开始时间止不能为空")
    private LocalDateTime startTimeTo;

    private Integer page = 1;

    private Integer pageSize = 10;
}
