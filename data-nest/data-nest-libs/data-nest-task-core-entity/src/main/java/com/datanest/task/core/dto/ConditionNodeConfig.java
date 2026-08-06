package com.datanest.task.core.dto;

import lombok.Data;

import java.util.List;

/**
 * 条件分支节点配置（Sprint 5，task-core 共享）。
 * config JSON: {"type":"CONDITION","branches":[{branchName,expression,nextNodeId},...]}
 * 约定：branches[0] 为默认兜底分支；分支按顺序求值，第一个满足条件的执行。
 */
@Data
public class ConditionNodeConfig {

    private String type = "CONDITION";

    private List<ConditionBranch> branches;

    @Data
    public static class ConditionBranch {
        private String branchName;
        /** 表达式，如 ${upstream.row_count} > 0 */
        private String expression;
        /** 满足条件时走向的下游节点 ID */
        private String nextNodeId;
    }
}
