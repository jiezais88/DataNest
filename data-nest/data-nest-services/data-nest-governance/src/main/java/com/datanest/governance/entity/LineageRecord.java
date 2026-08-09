package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "血缘记录（表级 + 字段级）")
public class LineageRecord {

    @Schema(description = "主键 ID", example = "1234567890123456789")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "源表")
    private String sourceTable;

    @Schema(description = "目标表")
    private String targetTable;

    /** 源字段，字段级血缘时使用 */
    @Schema(description = "源字段，字段级血缘时使用；为空表示表级血缘")
    private String sourceColumn;

    /** 目标字段，字段级血缘时使用 */
    @Schema(description = "目标字段，字段级血缘时使用；为空表示表级血缘")
    private String targetColumn;

    @Schema(description = "来源 DAG ID", example = "1234567890123456789")
    private Long dagId;

    @Schema(description = "来源 DAG 名称")
    private String dagName;

    @Schema(description = "来源节点 node_id")
    private String nodeId;

    @Schema(description = "来源节点名称")
    private String nodeName;

    @Schema(description = "来源执行 ID", example = "1234567890123456789")
    private Long executionId;

    @Schema(description = "血缘类型（SQL / SYNC / PYTHON）")
    private String lineageType;     // SQL / SYNC / PYTHON

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
}
