package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("metadata_table")
@Schema(description = "元数据表")
public class MetadataTable {

    @Schema(description = "主键 ID", example = "1234567890123456789")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "数据库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "表注释（采集自数据源）")
    private String tableComment;

    @Schema(description = "人工补充注释")
    private String manualComment;

    @Schema(description = "元数据状态（ONLINE / OFFLINE）")
    private String sourceStatus;

    @Schema(description = "数据源类型（EXTERNAL：外部数据源 / BUILTIN_DORIS：Doris 数仓）")
    private String sourceType;

    /** Sprint 4 Phase 1：任务来源类型（COLLECT / SYNC / SQL / PYTHON），与 source_type 区分 */
    @Schema(description = "任务来源类型（COLLECT/SYNC/SQL/PYTHON），与 sourceType 区分")
    private String taskSourceType;

    /** Sprint 4 Phase 1：来源 DAG ID */
    @Schema(description = "来源 DAG ID", example = "1234567890123456789")
    private Long sourceDagId;

    /** Sprint 4 Phase 1：来源 DAG 名称 */
    @Schema(description = "来源 DAG 名称")
    private String sourceDagName;

    /** Sprint 4 Phase 1：来源节点 node_id */
    @Schema(description = "来源节点 node_id")
    private String sourceNodeId;

    /** Sprint 4 Phase 1：来源节点名称 */
    @Schema(description = "来源节点名称")
    private String sourceNodeName;

    @Schema(description = "最近一次采集历史 ID", example = "1234567890123456789")
    private Long lastCollectHistoryId;

    /** Sprint 7 F1：数据域（一级分类名，冗余存名称便于展示） */
    @Schema(description = "数据域（一级分类名，冗余存名称便于展示）")
    private String dataDomain;

    /** Sprint 7 F1：主题（二级分类名，冗余存名称） */
    @Schema(description = "主题（二级分类名，冗余存名称）")
    private String dataTopic;

    /** Sprint 7 F1：表负责人用户 ID（关联 sys_user.id） */
    @Schema(description = "表负责人用户 ID（关联 sys_user.id）", example = "1234567890123456789")
    private Long ownerUserId;

    @Schema(description = "字段数量（非持久化，查询时统计回填）")
    @TableField(exist = false)
    private Integer columnCount;

    @Schema(description = "最近采集时间（非持久化，查询时回填）（ISO 8601）")
    @TableField(exist = false)
    private LocalDateTime lastCollectTime;

    @Schema(description = "来源任务名称（非持久化，回填展示用）")
    @TableField(exist = false)
    private String sourceTaskName;

    @Schema(description = "数据源名称（非持久化，回填展示用）")
    @TableField(exist = false)
    private String datasourceName;

    @Schema(description = "数据源类型（非持久化，回填展示用）")
    @TableField(exist = false)
    private String datasourceType;

    /** Sprint 7 F1：负责人用户名（回填展示用） */
    @Schema(description = "负责人用户名（非持久化，回填展示用）")
    @TableField(exist = false)
    private String ownerName;

    @Schema(description = "创建人用户 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "更新人用户 ID", example = "1234567890123456789")
    private Long updatedBy;

    @Schema(description = "创建人用户名（非持久化，回填展示用）")
    @TableField(exist = false)
    private String createdByName;

    @Schema(description = "更新人用户名（非持久化，回填展示用）")
    @TableField(exist = false)
    private String updatedByName;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
