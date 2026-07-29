package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collect_history")
public class CollectHistory {

    @TableId(type = IdType.ASSIGN_ID)
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
}
