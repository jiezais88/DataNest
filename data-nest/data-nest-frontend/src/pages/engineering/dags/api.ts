// DAG API：统一走全局 request 实例（token 注入、401 跳登录、业务错误提示都在拦截器里）。
// 后端 /engineering/dev/* 与 /engineering/dag-executions 均返回 {code, message, data} 信封（Result），
// 拦截器负责 code !== 200 时 reject；本层统一 .then(r => r.data) 拆信封，与老接口（sync.ts 等）用法一致。
import request from '../../../api/request';
import type {PageResult, Result} from '../../../types/common';
import type {Dag, DagExecution, DagProject, SqlPreviewResult} from './types';

// 执行类操作（trigger/rerun/sql-preview）可能超过全局 10s 默认超时
const LONG_TIMEOUT = 30000;

// =================== DAG Project ===================
// 路径对齐 ADR-S3-010：/api/engineering/dev/dag-projects
// gateway 配 /api/engineering/** + StripPrefix=1 → /engineering/dev/dag-projects
// engineering 服务 context-path=/engineering + controller=/dev/dag-projects ✓

export const listDagProjects = (params: { name?: string; page?: number; pageSize?: number }) =>
    request.get<Result<PageResult<DagProject>>>('/engineering/dev/dag-projects', {params}).then(r => r.data);
export const getDagProject = (id: string | number) =>
    request.get<Result<DagProject>>(`/engineering/dev/dag-projects/${id}`).then(r => r.data);
export const createDagProject = (data: DagProject) =>
    request.post<Result<DagProject>>('/engineering/dev/dag-projects', data).then(r => r.data);
export const updateDagProject = (id: string | number, data: DagProject) =>
    request.put<Result<DagProject>>(`/engineering/dev/dag-projects/${id}`, data).then(r => r.data);
export const deleteDagProject = (id: string | number) =>
    request.delete<Result<null>>(`/engineering/dev/dag-projects/${id}`).then(r => r.data);

// =================== DAG ===================
// 所有 id 入参用 string | number：19 位 Snowflake id 超过 JS Number.MAX_SAFE_INTEGER
// 必须保持 string 避免精度丢失（axios URL 拼接时如果前端先 Number() 会被截断）

export const listDags = (projectId?: string | number) =>
    request.get<Result<Dag[]>>('/engineering/dev/dags', {params: {projectId}}).then(r => r.data);
export const getDag = (id: string | number) =>
    request.get<Result<Dag>>(`/engineering/dev/dags/${id}`).then(r => r.data);
export const createDag = (data: Dag) =>
    request.post<Result<Dag>>('/engineering/dev/dags', data).then(r => r.data);
export const updateDag = (id: string | number, data: Dag) =>
    request.put<Result<Dag>>(`/engineering/dev/dags/${id}`, data).then(r => r.data);
export const deleteDag = (id: string | number) =>
    request.delete<Result<null>>(`/engineering/dev/dags/${id}`).then(r => r.data);

export const triggerDag = (id: string | number) =>
    request.post<Result<DagExecution>>(`/engineering/dev/dags/${id}/trigger`, undefined, {timeout: LONG_TIMEOUT}).then(r => r.data);
export const startDagSchedule = (id: string | number) =>
    request.post<Result<null>>(`/engineering/dev/dags/${id}/schedule/start`).then(r => r.data);
export const stopDagSchedule = (id: string | number) =>
    request.post<Result<null>>(`/engineering/dev/dags/${id}/schedule/stop`).then(r => r.data);
export const stopDag = (id: string | number, executionId: string | number) =>
    request.post<Result<null>>(`/engineering/dev/dags/${id}/executions/${executionId}/stop`).then(r => r.data);
export const listDagExecutions = (id: string | number) =>
    request.get<Result<DagExecution[]>>(`/engineering/dev/dags/${id}/executions`).then(r => r.data);

/**
 * 获取某次 DAG 执行详情（复用列表端点，避免新增后端接口）。
 * 19 位 Snowflake id 全程以 string 比较，防止精度丢失。
 */
export const getDagExecution = async (dagId: string | number, executionId: string | number) => {
    const list = await listDagExecutions(dagId);
    const found = list.find(e => String(e.id) === String(executionId));
    if (!found) throw new Error('执行实例不存在');
    return found;
};

// Sprint 3 P1-13（差距分析 §1.13）：重跑失败节点
// 复用 trigger：Mvp 简化版会重新跑所有节点，真正的"只重跑失败节点"留 P2
export const rerunFailed = (dagId: string | number, executionId: string | number) =>
    request.post<Result<DagExecution>>(`/engineering/dev/dags/${dagId}/executions/${executionId}/rerun-failed`, undefined, {timeout: LONG_TIMEOUT}).then(r => r.data);

// =================== SQL Preview ===================
// Sprint 3: "Run Test" button in the SQL editor modal.
// Calls backend POST /api/engineering/dev/sql-preview, returns parsed result
// with one entry per statement (split by ';', string-literal aware).
export const previewSql = (sql: string, datasourceId?: number) =>
    request.post<{ code: number; message?: string; data: SqlPreviewResult }>(
        '/engineering/dev/sql-preview',
        {sql, datasourceId},
        {timeout: LONG_TIMEOUT, skipErrorMessage: true},
    ).then(r => r.data);

// =================== Global DAG Execution History ===================
// PRD §6.7.3 全局执行历史：跨 DAG 的运行实例查询，支持按名称/状态/触发方式/时间范围过滤
// 后端路径 /api/engineering/dag-executions（不在 /dev/* 命名空间下——这是跨工程的全局视图）

export const listAllDagExecutions = (params: {
    dagName?: string;
    /** 所属项目名称模糊匹配 */
    projectName?: string;
    /** DAG id 精确过滤（任务列表「历史」跳入时只展示该 DAG） */
    dagId?: string;
    status?: string;
    triggerType?: string;
    startTimeFrom?: string;
    startTimeTo?: string;
    page?: number;
    pageSize?: number;
}) => request.get<Result<PageResult<DagExecution>>>('/engineering/dag-executions', {params}).then(r => r.data);
