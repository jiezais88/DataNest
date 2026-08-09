package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 条件分支节点配置（Sprint 5，task-core 共享）。
 * config JSON: {"type":"CONDITION","branches":[{branchName,expression,nextNodeId},...]}
 * 约定：branches[0] 为默认兜底分支；分支按顺序求值，第一个满足条件的执行。
 */
@Schema(description = "条件分支节点配置")
@Data
public class ConditionNodeConfig {

    @Schema(description = "节点类型（固定 CONDITION）")
    private String type = "CONDITION";

    @Schema(description = "分支列表（按顺序求值，第一个满足条件的执行；branches[0] 为默认兜底分支）")
    private List<ConditionBranch> branches;

    @Schema(description = "条件分支")
    @Data
    public static class ConditionBranch {
        @Schema(description = "分支名称")
        private String branchName;
        @Schema(description = "条件表达式，如 ${upstream.row_count} > 0")
        private String expression;
        @Schema(description = "满足条件时走向的下游节点 ID")
        private String nextNodeId;
    }
}
