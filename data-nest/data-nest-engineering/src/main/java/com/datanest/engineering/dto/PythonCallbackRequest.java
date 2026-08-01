package com.datanest.engineering.dto;

import lombok.Data;

/**
 * PYTHON 节点回调入参
 */
@Data
public class PythonCallbackRequest {

    private Long dagId;
    private Long executionId;
    private String nodeId;
}
