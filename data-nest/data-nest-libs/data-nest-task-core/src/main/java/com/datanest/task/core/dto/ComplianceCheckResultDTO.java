package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准合规检查结果 DTO。从 governance 模块下沉至共享底座。
 * Sprint6 扩展：增加 ignored/ignoredAt/ignoredBy 三字段。
 */
@Schema(description = "标准合规检查结果")
@Data
public class ComplianceCheckResultDTO {

    @Schema(description = "记录 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "数据标准 ID", example = "1234567890123456789")
    private Long standardId;

    @Schema(description = "数据标准名称")
    private String standardName;

    @Schema(description = "对象类型")
    private String objectType;

    @Schema(description = "对象路径")
    private String objectPath;

    @Schema(description = "违规类型（NAMING 命名规范/TYPE 字段类型）")
    private String violationType;

    @Schema(description = "表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "字段 ID", example = "1234567890123456789")
    private Long columnId;

    @Schema(description = "字段名")
    private String columnName;

    @Schema(description = "对象名称")
    private String objectName;

    @Schema(description = "实际值")
    private String actualValue;

    @Schema(description = "期望值")
    private String expectedValue;

    @Schema(description = "适用标准列表")
    private List<ApplicableStandardDTO> applicableStandards;

    @Schema(description = "是否合规（1 合规，0 不合规）")
    private Integer isCompliant;

    @Schema(description = "检查时间（ISO 8601）")
    private LocalDateTime checkedAt;

    @Schema(description = "是否已忽略（1 已忽略，0 未忽略）")
    private Integer ignored;

    @Schema(description = "忽略时间（ISO 8601）")
    private LocalDateTime ignoredAt;

    @Schema(description = "忽略操作人 ID", example = "1234567890123456789")
    private Long ignoredBy;

    @Schema(description = "适用标准")
    @Data
    public static class ApplicableStandardDTO {
        @Schema(description = "标准名称")
        private String standardName;
        @Schema(description = "规则类型")
        private String ruleType;
        @Schema(description = "规则值")
        private String ruleValue;
        @Schema(description = "允许的字段类型列表")
        private List<String> allowedTypes;
    }
}
