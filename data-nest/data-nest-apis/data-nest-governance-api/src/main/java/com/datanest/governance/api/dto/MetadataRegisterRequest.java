package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 元数据注册请求。
 * <p>
 * 对齐原 MetadataRegistrationService.registerTable 的入参语义：
 * 内置 Doris 表（datasource_id=-1）的 findOrCreateTable + refreshColumns + column_count 更新，
 * 在治理服务端一个事务内完成。
 */
@Data
public class MetadataRegisterRequest {

    /** 目标库名 */
    private String databaseName;

    /** 目标表名 */
    private String tableName;

    /** 字段列表（由调用方从 Doris information_schema 提取后上报） */
    private List<MetadataRegisterColumnDTO> columns;

    /** 任务来源类型（COLLECT / SYNC / SQL / PYTHON） */
    private String sourceTaskType;

    /** 来源 DAG ID */
    private Long sourceDagId;

    /** 来源 DAG 名称 */
    private String sourceDagName;

    /** 来源节点 node_id */
    private String sourceNodeId;

    /** 来源节点名称 */
    private String sourceNodeName;

    /** 操作人 ID（可选；created_by 仅在为空时回填，updated_by 按本次注册覆盖） */
    private Long operatorId;
}
