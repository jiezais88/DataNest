package com.datanest.common.constant;

/**
 * 内置 Doris 数仓相关常量。
 * <p>
 * 收敛来源（2026-08-12）：governance（QualityScoreService/QualityRuleService/QualityReportService/
 * MetadataWriteService）、task-core（QualityCheckService/PythonConnectionResolver）、
 * data-service（SqlQueryService）此前各自用字面量 -1L 或同名私有常量，统一收敛到此处。
 */
public final class DorisConstants {

    private DorisConstants() {
    }

    /** 内置 Doris 数据源 ID（datasource_id=-1，datasource 表无记录，与外部数据源区分） */
    public static final long BUILTIN_DORIS_DATASOURCE_ID = -1L;

    /** 内置 Doris 数仓展示名 */
    public static final String BUILTIN_DORIS_NAME = "Doris 数仓";
}
