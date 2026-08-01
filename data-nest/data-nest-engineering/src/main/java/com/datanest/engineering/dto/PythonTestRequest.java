package com.datanest.engineering.dto;

import lombok.Data;

import java.util.Map;

/**
 * PYTHON 节点测试入参
 */
@Data
public class PythonTestRequest {

    private String pythonScript;
    private Map<String, Object> params;
}
