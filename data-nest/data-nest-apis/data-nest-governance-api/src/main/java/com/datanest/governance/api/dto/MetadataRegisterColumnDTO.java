package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 元数据注册请求中的字段信息。
 * <p>
 * 对齐原 MetadataRegistrationService.extractColumns 从 information_schema 提取的字段结构，
 * 由调用方（task 服务）完成 Doris 列提取后上报。
 */
@Data
public class MetadataRegisterColumnDTO {

    /** 字段名 */
    private String columnName;

    /** 数据类型（对应 metadata_column.data_type） */
    private String columnType;

    /** 字段注释（仅新字段插入时生效；已有字段的 column_comment/manual_comment 不被覆盖） */
    private String comment;

    /** 是否可空 */
    private Boolean nullable;

    /** 默认值 */
    private String columnDefault;

    /** 字段顺序 */
    private Integer ordinalPosition;
}
