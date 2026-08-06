package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectExecutionLogDTO {

    private Long id;

    private Long historyId;

    private Long taskId;

    private String level;

    private String message;

    private LocalDateTime createdAt;
}
