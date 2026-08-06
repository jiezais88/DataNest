package com.datanest.engineering.dto;

import lombok.Data;

import java.util.List;

/**
 * DS TaskDefinition 概要（DS 流程里的单个任务）
 * 用于把 DataNest DagNode 转为 DS TaskDefinition
 * 决策 ADR-S3-FJ：使用 fastjson2 替代 Jackson，preTaskCodes 字段映射为 preTasks
 */
@Data
public class DsTaskDefinition {

    /** DataNest 侧预生成后传入；DS API 也会返回 */
    private Long code;

    private String name;

    private String description;

    /** SHELL / SQL / HTTP / ... */
    private String taskType;

    /**
     * 任务参数 JSON 字符串
     * SQL: {"localParams":[],"rawScript":"SELECT 1","datasource":1,"sqlType":"0"}
     * HTTP: {"localParams":[],"httpParams":{"url":"...","method":"GET","headers":...,"body":...}}
     * SHELL: {"localParams":[],"rawScript":"echo hello"}
     */
    private String taskParams;

    /** Worker 分组 */
    private String workerGroup;

    /** 启用标志：YES / NO */
    private String flag;

    /** 失败重试次数 */
    private Integer failRetryTimes;

    /** 失败重试间隔（分钟） */
    private Integer failRetryInterval;

    /** 超时标志：OPEN / CLOSE */
    private String timeoutFlag;

    /** 超时告警策略：SUCCESS / FAILED / WARN 等 */
    private String timeoutNotifyStrategy;

    /** 超时时间（分钟） */
    private Integer timeout;

    /** 延迟执行时间（分钟） */
    private Integer delayTime;

    /** 资源中心文件列表 */
    private List<?> resourceList;

    /** 环境 code（-1 表示默认） */
    private Long environmentCode = -1L;

    private String conditionType;        // NONE / SUCCESS / FAILURE

    /** Sprint 3 API 测试发现：DS 拒收缺 version/taskPriority 的 taskDefinitionJson */
    private Integer version = 1;

    private String taskPriority = "MEDIUM";  // HIGH/MEDIUM/LOW

    private String taskExecuteType = "BATCH"; // BATCH / STREAM
}
