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

    /**
     * 测试执行超时秒数，默认 30 分钟；前端/测试可指定较小值验证超时终止。
     */
    private Integer timeoutSeconds;

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
