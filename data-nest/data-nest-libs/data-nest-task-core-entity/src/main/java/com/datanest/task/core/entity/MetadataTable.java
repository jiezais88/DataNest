package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("metadata_table")
public class MetadataTable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long datasourceId;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String tableComment;

    private String manualComment;

    private String sourceStatus;

    private String sourceType;

    /** Sprint 4 Phase 1：任务来源类型（COLLECT / SYNC / SQL / PYTHON），与 source_type 区分 */
    private String taskSourceType;

    /** Sprint 4 Phase 1：来源 DAG ID */
    private Long sourceDagId;

    /** Sprint 4 Phase 1：来源 DAG 名称 */
    private String sourceDagName;

    /** Sprint 4 Phase 1：来源节点 node_id */
    private String sourceNodeId;

    /** Sprint 4 Phase 1：来源节点名称 */
    private String sourceNodeName;

    private Long lastCollectHistoryId;

    /** Sprint 7 F1：数据域（一级分类名，冗余存名称便于展示） */
    private String dataDomain;

    /** Sprint 7 F1：主题（二级分类名，冗余存名称） */
    private String dataTopic;

    /** Sprint 7 F1：表负责人用户 ID（关联 sys_user.id） */
    private Long ownerUserId;

    @TableField(exist = false)
    private Integer columnCount;

    @TableField(exist = false)
    private LocalDateTime lastCollectTime;

    @TableField(exist = false)
    private String sourceTaskName;

    @TableField(exist = false)
    private String datasourceName;

    @TableField(exist = false)
    private String datasourceType;

    /** Sprint 7 F1：负责人用户名（回填展示用） */
    @TableField(exist = false)
    private String ownerName;

    private Long createdBy;

    private Long updatedBy;

    @TableField(exist = false)
    private String createdByName;

    @TableField(exist = false)
    private String updatedByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
