package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据分级变更审计（Sprint 10 F5，V1.6.0 + V1.7.0）：谁 / 何时 / 从哪级到哪级 + 动作类型。
 * <p>
 * 轻量独立审计表（项目无通用审计体系，规格 Sprint 12 才做）。改级（CHANGE_LEVEL）与开白（API_EXEMPT）
 * 均写入，治理员/超管可查。操作人删除后仍保留 operator_id（展示「已注销」）。
 */
@Data
@TableName("sensitivity_change_log")
@Schema(description = "数据分级变更审计")
public class SensitivityChangeLog {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "元数据表 ID")
    private Long tableId;

    @Schema(description = "表名（冗余，表删除后仍可读）")
    private String tableName;

    @Schema(description = "原敏感度（PUBLIC/INTERNAL/CONFIDENTIAL；首次打标为 NULL）")
    private String oldLevel;

    @Schema(description = "新敏感度（PUBLIC/INTERNAL/CONFIDENTIAL）")
    private String newLevel;

    @Schema(description = "审计动作：CHANGE_LEVEL 改级 / API_EXEMPT 开白")
    private String action;

    @Schema(description = "动作补充说明（API_EXEMPT 时的 开白/取消开白）")
    private String remark;

    @Schema(description = "操作人用户 ID")
    private Long operatorId;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;
}
