package com.datanest.dataservice.dto;

import lombok.Data;

import java.util.List;

/**
 * API 定义（data_api.params_json 的解析形态）：filters 参数化筛选 + fields 返回字段白名单（选表形态）；queryType + sqlParams（CUSTOM_SQL 形态）。
 */
@Data
public class DataApiDefinition {

    /** 参数化筛选（EQ/RANGE），查询条件组合为 AND */
    private List<ApiParamDef> filters;

    /** 返回字段白名单；null/空 = 全部字段 */
    private List<String> fields;

    /** 查询定义形态：TABLE_SELECT 选表 / CUSTOM_SQL 自定义 SQL（Sprint 13） */
    private String queryType;

    /** 自定义 SQL 参数定义（CUSTOM_SQL 形态，按名称与 SQL :param 一一对应） */
    private List<CustomSqlParamDef> sqlParams;
}
