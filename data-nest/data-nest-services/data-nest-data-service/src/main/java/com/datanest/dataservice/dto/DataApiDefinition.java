package com.datanest.dataservice.dto;

import lombok.Data;

import java.util.List;

/**
 * API 定义（data_api.params_json 的解析形态）：filters 参数化筛选 + fields 返回字段白名单。
 */
@Data
public class DataApiDefinition {

    /** 参数化筛选（EQ/RANGE），查询条件组合为 AND */
    private List<ApiParamDef> filters;

    /** 返回字段白名单；null/空 = 全部字段 */
    private List<String> fields;
}
