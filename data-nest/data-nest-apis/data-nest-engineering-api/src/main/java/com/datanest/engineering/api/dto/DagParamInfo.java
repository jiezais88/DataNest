package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * DAG 自定义参数。
 */
@Data
public class DagParamInfo {

    private Long id;

    private Long dagId;

    private String paramName;

    private String paramType;

    private String defaultValue;

    private Integer required;

    private String description;
}
