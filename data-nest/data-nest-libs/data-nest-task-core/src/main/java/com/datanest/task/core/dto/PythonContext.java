package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Schema(description = "Python 脚本执行上下文（注入脚本内置 helper）")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PythonContext {

    @Schema(description = "DAG ID", example = "1234567890123456789")
    private Long dagId;

    @Schema(description = "执行 ID", example = "1234567890123456789")
    private Long executionId;

    @Schema(description = "节点 ID")
    private String nodeId;

    @Schema(description = "上下文参数")
    private Map<String, Object> params = new HashMap<>();

    public PythonContext(Map<String, Object> params) {
        this.params = params == null ? new HashMap<>() : params;
    }
}
