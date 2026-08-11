// Sprint 8 F2：实时 CDC 管道类型（对齐后端 realtime CdcPipelineController DTO）
// 注意：Long 字段（id/totalChanges/各计数）后端序列化为 string；展示直接用，运算先 Number()。

/** 管道状态 */
export type CdcPipelineStatus = 'STOPPED' | 'RUNNING' | 'ERROR';
/** 同步模式：FULL_AND_INCREMENT 全量+增量 / INCREMENTAL_ONLY 仅增量 */
export type CdcSyncMode = 'FULL_AND_INCREMENT' | 'INCREMENTAL_ONLY';
/** 启动位点：INITIAL 全量快照+增量 / LATEST_OFFSET 从最新 / EARLIEST_OFFSET 从最早（仅增量模式可选） */
export type CdcStartupMode = 'INITIAL' | 'LATEST_OFFSET' | 'EARLIEST_OFFSET';
/** 写入模式：UPSERT 主键覆盖（每表必须配主键）/ APPEND 追加 */
export type CdcWriteMode = 'UPSERT' | 'APPEND';

/** 表级映射 */
export interface CdcTableMapping {
    /** 源表名（不含库名） */
    sourceTable: string;
    /** 目标表名（可空，后端默认同源表名） */
    targetTable?: string;
    /** 目标表主键列（逗号分隔，UPSERT 必填） */
    primaryKey?: string;
}

/** 管道详情/列表项 */
export interface CdcPipeline {
    id: string;
    name: string;
    description?: string;
    sourceDatasourceId: string;
    /** 源数据源名称（跨域回填，失败缺省） */
    sourceDatasourceName?: string;
    sourceDatabase: string;
    targetDatabase: string;
    syncMode: CdcSyncMode;
    startupMode: CdcStartupMode;
    writeMode: CdcWriteMode;
    status: CdcPipelineStatus;
    /** RUNNING 时有值 */
    flinkJobId?: string;
    /** 最近一次 savepoint 路径（启动优先恢复；编辑后清空） */
    savepointPath?: string;
    /** 当前同步延迟（秒；Long→string 不适用，后端是 Integer） */
    currentLagSeconds?: number;
    /** 累计写入变更条数（Long 序列化为 string） */
    totalChanges?: string;
    lastError?: string;
    /** 高级配置 JSON，约定键：parallelism（1~8）、checkpointIntervalSeconds（≥3）；缺键走 Nacos 默认 */
    configJson?: string;
    tables?: CdcTableMapping[];
    /** 最近一次启动成功时间（ISO；stop 不清，编辑不影响） */
    startedAt?: string;
    createdBy?: string;
    updatedBy?: string;
    /** 创建人/修改人显示名（跨域回填，失败缺省） */
    createdByName?: string;
    updatedByName?: string;
    createdAt?: string;
    updatedAt?: string;
}

/** 新增/编辑请求 */
export interface CdcPipelineSaveRequest {
    name: string;
    description?: string;
    sourceDatasourceId: string;
    sourceDatabase: string;
    targetDatabase: string;
    syncMode: CdcSyncMode;
    startupMode: CdcStartupMode;
    writeMode: CdcWriteMode;
    tables: CdcTableMapping[];
    configJson?: string;
}

/** 分页查询参数（GET /cdc/pipelines/page） */
export interface CdcPipelineQuery {
    status?: CdcPipelineStatus | '';
    keyword?: string;
    page?: number;
    pageSize?: number;
}

/** 列表页顶部统计卡（各计数 Long 序列化为 string） */
export interface CdcPipelineStats {
    running?: string;
    stopped?: string;
    error?: string;
    syncedTables?: string;
}

/** 源预检单项 */
export interface CdcCheckItem {
    name: string;
    passed: boolean;
    message?: string;
}
/** 源表信息（向导同步表勾选；tableRows 为 InnoDB 约估行数，Long 序列化为 string） */
export interface CdcSourceTable {
    tableName: string;
    tableRows?: string;
    /** 源表主键列（逗号分隔，无主键缺省/null；用于勾选时预填映射主键） */
    primaryKey?: string;
}

/** 源预检结果 */
export interface CdcSourceValidateResult {
    success: boolean;
    checks?: CdcCheckItem[];
}

/** Flink 集群容量（向导并行度动态提示；集群不可达时字段为空，前端降级通用提示） */
export interface CdcClusterInfo {
    slotsTotal?: number;
    slotsAvailable?: number;
}

/** 运行日志项 */
export interface CdcPipelineLog {
    id: string;
    /** INFO / WARN / ERROR */
    level: string;
    message: string;
    createdAt?: string;
}
