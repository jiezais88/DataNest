package com.datanest.task.core.dto;

import lombok.Data;

/**
 * Python 任务节点配置（config JSON 解析后的对象）
 */
@Data
public class PythonNodeConfig {

    /**
     * 节点类型常量：PYTHON
     */
    public static final String TYPE = "PYTHON";

    private String pythonScript;

    /** 超时分钟数，默认 30 */
    private Integer timeoutMinutes;

    /** 内存限制 MB，默认 2048（Sprint 4 仅作为配置保留，进程级强限制后续实现） */
    private Integer memoryLimitMb;
}
