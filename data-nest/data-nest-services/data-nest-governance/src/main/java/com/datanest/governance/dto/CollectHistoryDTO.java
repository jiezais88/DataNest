package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CollectHistoryDTO {

    private Long id;

    private Long taskId;

    private String taskName;

    private Long datasourceId;

    private String triggerType;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private Long durationMs;

    private Integer dbCount;

    private Integer tableCount;

    private Integer columnCount;

    private Integer addedTableCount;

    private Integer updatedTableCount;

    private Integer deletedTableCount;

    private Integer addedColumnCount;

    private Integer updatedColumnCount;

    private Integer deletedColumnCount;

    private String errorMessage;

    private LocalDateTime createdAt;

    private List<CollectChangeDetailDTO> changeDetails;
}
