// Sprint 8 F2：实时 CDC 管道 API（realtime CdcPipelineController，/realtime/cdc/pipelines/**）
// 读接口四角色可用；写接口（增删改/启停/预检/刷 catalog）仅超管+数据工程师（后端 SaCheckRole 兜底）。
import request from './request';
import type {PageResult, Result} from '../types/common';
import type {
    CdcPipeline,
    CdcPipelineLog,
    CdcPipelineQuery,
    CdcPipelineSaveRequest,
    CdcPipelineStats,
    CdcSourceTable,
    CdcSourceValidateResult,
} from '../types/cdc';

const BASE = '/realtime/cdc/pipelines';

/** 源数据源预检（连通性/binlog 开启/ROW 格式/源库存在性逐项） */
export const validateCdcSource = (datasourceId: string, sourceDatabase?: string) =>
    request.post<Result<CdcSourceValidateResult>>(`${BASE}/validate-source`, {datasourceId, sourceDatabase}).then(r => r.data);

/** 源 MySQL 库列表（向导选库下拉，已过滤系统库） */
export const listCdcSourceDatabases = (datasourceId: string) =>
    request.get<Result<string[]>>(`${BASE}/source-databases/${datasourceId}`).then(r => r.data);

/** 源库表列表（向导勾选同步表；表名 + 约估行数 + 主键列） */
export const listCdcSourceTables = (datasourceId: string, database: string) =>
    request.get<Result<CdcSourceTable[]>>(`${BASE}/source-tables/${datasourceId}`, {params: {database}}).then(r => r.data);

/** 现有湖仓库名列表（向导目标库下拉；允许自由输入新库名，Iceberg namespace 自动创建） */
export const listCdcTargetDatabases = () =>
    request.get<Result<string[]>>(`${BASE}/target-databases`).then(r => r.data);

/** 创建管道（初始 STOPPED；UPSERT 模式每表必须配主键） */
export const createCdcPipeline = (data: CdcPipelineSaveRequest) =>
    request.post<Result<CdcPipeline>>(BASE, data).then(r => r.data);

/** 管道分页（状态/名称关键字，id 倒序） */
export const pageCdcPipelines = (params: CdcPipelineQuery) =>
    request.get<Result<PageResult<CdcPipeline>>>(`${BASE}/page`, {params}).then(r => r.data);

/** 管道统计（运行中/已停止/异常 + 已同步表总数） */
export const getCdcPipelineStats = () =>
    request.get<Result<CdcPipelineStats>>(`${BASE}/stats`).then(r => r.data);

/** 管道详情（含表级映射与源数据源名） */
export const getCdcPipeline = (id: string) =>
    request.get<Result<CdcPipeline>>(`${BASE}/${id}`).then(r => r.data);

/** 编辑管道（仅 STOPPED 可编辑；全量替换表映射并清空 savepoint） */
export const updateCdcPipeline = (id: string, data: CdcPipelineSaveRequest) =>
    request.put<Result<CdcPipeline>>(`${BASE}/${id}`, data).then(r => r.data);

/** 删除管道（运行中禁止，后端 8008 兜底；级联删表映射与日志） */
export const deleteCdcPipeline = (id: string) =>
    request.delete<Result<null>>(`${BASE}/${id}`).then(r => r.data);

/** 启动管道（有 savepoint 优先恢复；失败置 ERROR 并抛 8007） */
export const startCdcPipeline = (id: string) =>
    request.post<Result<CdcPipeline>>(`${BASE}/${id}/start`).then(r => r.data);

/** 停止管道（cancel-with-savepoint，供下次启动恢复） */
export const stopCdcPipeline = (id: string) =>
    request.post<Result<CdcPipeline>>(`${BASE}/${id}/stop`).then(r => r.data);

/** 管道运行日志（id 倒序分页） */
export const getCdcPipelineLogs = (id: string, page: number, pageSize: number) =>
    request.get<Result<PageResult<CdcPipelineLog>>>(`${BASE}/${id}/logs`, {params: {page, pageSize}}).then(r => r.data);

/** 刷新 Doris catalog（湖仓新表/新数据让 Doris 外部表可见） */
export const refreshCdcCatalog = (id: string) =>
    request.get<Result<null>>(`${BASE}/${id}/refresh-catalog`).then(r => r.data);
