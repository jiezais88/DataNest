package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * PYTHON 节点测试入参
 */
@Schema(description = "PYTHON 节点测试入参")
@Data
public class PythonTestRequest {

    @Schema(description = "Python 脚本内容")
    private String pythonScript;
    @Schema(description = "脚本参数（key → 取值）")
    private Map<String, Object> params;

    /**
     * 测试执行超时秒数，默认 30 分钟；前端/测试可指定较小值验证超时终止。
     */
    @Schema(description = "测试执行超时秒数（默认 30 分钟）")
    private Integer timeoutSeconds;

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
