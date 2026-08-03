package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 血缘记录（表级 + 字段级）
 * 对应表 lineage_record
 * Sprint 5：新增 source_column / target_column 支持字段级血缘；
 * 两者均为空时表示表级血缘。
 */
@Data
@TableName("lineage_record")
public class LineageRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String sourceTable;

    private String targetTable;

    /** 源字段，字段级血缘时使用 */
    private String sourceColumn;

    /** 目标字段，字段级血缘时使用 */
    private String targetColumn;

    private Long dagId;

    private String dagName;

    private String nodeId;

    private String nodeName;

    private Long executionId;

    private String lineageType;     // SQL / SYNC / PYTHON

    private LocalDateTime createdAt;
}
