package com.datanest.task.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Python 脚本执行上下文，会注入到脚本内置 helper 中。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PythonContext {

    private Long dagId;

    private Long executionId;

    private String nodeId;

    private Map<String, Object> params = new HashMap<>();

    public PythonContext(Map<String, Object> params) {
        this.params = params == null ? new HashMap<>() : params;
    }
}
