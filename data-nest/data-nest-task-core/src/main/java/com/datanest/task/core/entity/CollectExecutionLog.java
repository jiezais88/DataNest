package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collect_execution_log")
public class CollectExecutionLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long historyId;

    private Long taskId;

    private String level;

    private String message;

    private LocalDateTime createdAt;
}
