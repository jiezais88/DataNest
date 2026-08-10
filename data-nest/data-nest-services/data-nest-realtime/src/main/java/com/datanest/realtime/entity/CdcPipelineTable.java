package com.datanest.realtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CDC 管道表级映射（源表 → 目标表）。
 */
@Data
@TableName("cdc_pipeline_table")
public class CdcPipelineTable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属管道 ID */
    private Long pipelineId;

    /** 源表名（不含库名，库名取管道的 source_database） */
    private String sourceTable;

    /** 目标表名（为空时默认同源表名） */
    private String targetTable;

    /** 目标表主键列（逗号分隔，UPSERT 模式必填） */
    private String primaryKey;

    private LocalDateTime createdAt;
}
