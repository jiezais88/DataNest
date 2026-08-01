package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表级血缘记录
 * 对应表 lineage_record
 */
@Data
@TableName("lineage_record")
public class LineageRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String sourceTable;

    private String targetTable;

    private Long dagId;

    private String dagName;

    private String nodeId;

    private String nodeName;

    private Long executionId;

    private String lineageType;     // SQL / SYNC / PYTHON

    private LocalDateTime createdAt;
}
