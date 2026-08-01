package com.datanest.engineering.dto;

import lombok.Data;

/**
 * PYTHON 节点 config 解析对象
 */
@Data
public class PythonNodeConfig {

    private String type;
    private String pythonScript;
    private Integer timeoutMinutes;
    private Integer memoryLimitMb;
}
