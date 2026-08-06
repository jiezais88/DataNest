// DAG API：统一走全局 request 实例（token 注入、401 跳登录、业务错误提示都在拦截器里）。
// 后端 /engineering/dev/* 与 /engineering/dag-executions 均返回 {code, message, data} 信封（Result），
// 拦截器负责 code !== 200 时 reject；本层统一 .then(r => r.data) 拆信封，与老接口（sync.ts 等）用法一致。
import request from '../../../api/request';
import type {PageResult, Result} from '../../../types/common';
import type {SyncJobLog} from '../../../types/sync';
import type {AlertRuleDTO} from '../../../types/alert';
import type {
    Dag,
    DagAlertConfig,
    DagExecution,
    DagParameter,
    DagProject,
    DagVersion,
    DagVersionDiff,
    NodeExecutionLog,
    PythonExecuteResult,
    SqlPreviewResult,
} from './types';

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

export const triggerDag = (id: string | number, params?: Record<string, unknown>) =>
    request.post<Result<DagExecution>>(`/engineering/dev/dags/${id}/trigger`, params, {timeout: LONG_TIMEOUT}).then(r => r.data);
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

// Sprint 4：真正的重跑失败节点 —— 后端仅重跑 FAILED/SKIPPED 节点，成功节点结果复用
export const rerunFailed = (dagId: string | number, executionId: string | number) =>
    request.post<Result<DagExecution>>(`/engineering/dev/dags/${dagId}/executions/${executionId}/rerun-failed`, undefined, {timeout: LONG_TIMEOUT}).then(r => r.data);

// DAG 节点执行日志（运行画布 SYNC 节点「查看日志」）：
// 返回结构与同步任务日志接口 GET /sync-jobs/{id}/history/{historyId}/logs 一致（SyncJobLog[]），
// 因此直接复用 SyncJobLog 类型与 HistoryLogModal 组件
export const getNodeExecutionLogs = (nodeExecutionId: string | number) =>
    request.get<Result<SyncJobLog[]>>(`/engineering/dev/dags/node-executions/${nodeExecutionId}/logs`);

// =================== SQL Preview ===================
// Sprint 3: "Run Test" button in the SQL editor modal.
// Calls backend POST /api/engineering/dev/sql-preview, returns parsed result
// with one entry per statement (split by ';', string-literal aware).
export const previewSql = (sql: string, datasourceId?: number, params?: Record<string, unknown>) =>
    request.post<{ code: number; message?: string; data: SqlPreviewResult }>(
        '/engineering/dev/sql-preview',
        {sql, datasourceId, params},
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

// =================== DAG 参数（Sprint 4） ===================

export const listDagParameters = (dagId: string | number) =>
    request.get<Result<DagParameter[]>>(`/engineering/dev/dags/${dagId}/parameters`).then(r => r.data);
export const createDagParameter = (dagId: string | number, data: DagParameter) =>
    request.post<Result<DagParameter>>(`/engineering/dev/dags/${dagId}/parameters`, data).then(r => r.data);
export const updateDagParameter = (dagId: string | number, id: string | number, data: DagParameter) =>
    request.put<Result<DagParameter>>(`/engineering/dev/dags/${dagId}/parameters/${id}`, data).then(r => r.data);
export const deleteDagParameter = (dagId: string | number, id: string | number) =>
    request.delete<Result<null>>(`/engineering/dev/dags/${dagId}/parameters/${id}`).then(r => r.data);

// =================== Python 节点运行测试（Sprint 4） ===================
// 测试执行不注册元数据、不影响 DAG 执行状态；skipErrorMessage 由调用方在结果区行内展示错误

export const testPythonNode = (dagId: string | number, nodeId: string, pythonScript: string, params?: Record<string, unknown>, timeoutMinutes?: number) =>
    request.post<Result<PythonExecuteResult>>(
        `/engineering/dev/dags/${dagId}/nodes/${nodeId}/python/test`,
        {pythonScript, params},
        // axios 超时跟随脚本的超时配置（+30s 余量），避免用户设置 >10 分钟时前端先 abort 误报失败
        {timeout: (timeoutMinutes ?? 10) * 60000 + 30000, skipErrorMessage: true},
    ).then(r => r.data);

/**
 * 独立 Python 脚本测试：不依赖 DAG/节点 ID，用于新建 DAG 时尚未保存的场景。
 * 此时无法解析 DAG 级参数占位符，只执行脚本本身。
 */
export const testPythonScript = (pythonScript: string, params?: Record<string, unknown>, timeoutMinutes?: number) =>
    request.post<Result<PythonExecuteResult>>(
        '/engineering/dev/python/test',
        {pythonScript, params},
        {timeout: (timeoutMinutes ?? 10) * 60000 + 30000, skipErrorMessage: true},
    ).then(r => r.data);

// =================== DAG 版本（Sprint 4） ===================

export const listDagVersions = (dagId: string | number) =>
    request.get<Result<DagVersion[]>>(`/engineering/dev/dags/${dagId}/versions`).then(r => r.data);
export const compareDagVersions = (dagId: string | number, left: number, right: number) =>
    request.get<Result<DagVersionDiff>>(`/engineering/dev/dags/${dagId}/versions/compare`, {
        params: {
            left,
            right
        }
    }).then(r => r.data);
export const rollbackDagVersion = (dagId: string | number, versionNo: number) =>
    request.post<Result<DagVersion>>(`/engineering/dev/dags/${dagId}/versions/${versionNo}/rollback`).then(r => r.data);

// =================== 按 DAG 告警配置（Sprint 4） ===================
// 按 DAG 读取时后端会回退全局默认配置：响应 dagId 为 null 即表示当前继承全局配置
// 微服务化后由 app-alert 提供：/alert/dag-alert-config/dags/{dagId}

export const getDagAlertConfig = (dagId: string | number) =>
    request.get<Result<DagAlertConfig>>(`/alert/dag-alert-config/dags/${dagId}`).then(r => r.data);
export const putDagAlertConfig = (dagId: string | number, data: DagAlertConfig) =>
    request.put<Result<DagAlertConfig>>(`/alert/dag-alert-config/dags/${dagId}`, data).then(r => r.data);

// =================== 按 DAG 告警规则（Sprint 5，alert_rule 统一数据源） ===================
// 与全局告警中心同一数据源，任何入口修改实时同步
// 微服务化后统一走 app-alert 的 /alert/rules/by-object?objectType=DAG

export const getDagAlertRule = (dagId: string | number) =>
    request.get<Result<AlertRuleDTO>>('/alert/rules/by-object', {params: {objectType: 'DAG', objectId: dagId}}).then(r => r.data);
export const putDagAlertRule = (dagId: string | number, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>('/alert/rules/by-object', data, {params: {objectType: 'DAG', objectId: dagId}}).then(r => r.data);

// =================== 节点实时日志（Sprint 4） ===================
// 注意：节点日志统一走 /dag-executions/{executionId}/nodes/{nodeId}/logs

// RUNNING 轮询期间后端可能持续报错，禁用全局错误弹窗（面板内自行静默处理）
export const getNodeRuntimeLogs = (executionId: string | number, nodeId: string) =>
    request.get<Result<NodeExecutionLog[]>>(`/engineering/dag-executions/${executionId}/nodes/${nodeId}/logs`, {skipErrorMessage: true}).then(r => r.data);
