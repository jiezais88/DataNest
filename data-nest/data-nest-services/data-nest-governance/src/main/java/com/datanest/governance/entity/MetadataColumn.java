package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("metadata_column")
@Schema(description = "元数据字段")
public class MetadataColumn {

    @Schema(description = "主键 ID", example = "1234567890123456789")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属元数据表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "字段名")
    private String columnName;

    @Schema(description = "数据类型")
    private String dataType;

    @Schema(description = "字段注释（采集自数据源）")
    private String columnComment;

    @Schema(description = "人工补充注释")
    private String manualComment;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否可空")
    private Boolean nullable;

    @Schema(description = "字段默认值")
    private String columnDefault;

    @Schema(description = "字段顺序位置")
    private Integer ordinalPosition;

    @Schema(description = "最近一次采集历史 ID", example = "1234567890123456789")
    private Long lastCollectHistoryId;

    @Schema(description = "元数据状态（ONLINE / OFFLINE）")
    private String sourceStatus;

    @Schema(description = "数据源类型（EXTERNAL：外部数据源 / BUILTIN_DORIS：Doris 数仓）")
    private String sourceType;

    @Schema(description = "创建人用户 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "更新人用户 ID", example = "1234567890123456789")
    private Long updatedBy;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
