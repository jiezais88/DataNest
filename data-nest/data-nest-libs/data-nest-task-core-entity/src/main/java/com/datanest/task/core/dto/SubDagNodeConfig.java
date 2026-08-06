package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 子 DAG 节点配置（Sprint 5，task-core 共享）。
 * config JSON: {"type":"SUB_DAG","subDagId":123,"subDagName":"xxx","syncExecution":true}
 */
@Data
public class SubDagNodeConfig {

    private String type = "SUB_DAG";

    private Long subDagId;

    /** 冗余名称，便于展示 */
    private String subDagName;

    /** true=同步执行（等待子 DAG 完成后继续）；false=异步执行（触发后继续） */
    private Boolean syncExecution = true;
}
