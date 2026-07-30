// DAG API 客户端
import axios from 'axios';
import type {Dag, DagExecution, DagProject} from './types';

const http = axios.create({
    baseURL: '/api',
    timeout: 30000,
});

// 拦截器：自动加 token
http.interceptors.request.use(cfg => {
    const token = localStorage.getItem('token');
    if (token) cfg.headers.Authorization = `Bearer ${token}`;
    return cfg;
});

// =================== DAG Project ===================
// 路径对齐 ADR-S3-010：/api/engineering/dev/dag-projects
// gateway 配 /api/engineering/** + StripPrefix=1 → /engineering/dev/dag-projects
// engineering 服务 context-path=/engineering + controller=/dev/dag-projects ✓

export const listDagProjects = () => http.get<DagProject[]>('/engineering/dev/dag-projects').then(r => r.data);
export const createDagProject = (data: DagProject) => http.post<DagProject>('/engineering/dev/dag-projects', data).then(r => r.data);
export const updateDagProject = (id: number, data: DagProject) => http.put<DagProject>(`/engineering/dev/dag-projects/${id}`, data).then(r => r.data);
export const deleteDagProject = (id: number) => http.delete<void>(`/engineering/dev/dag-projects/${id}`).then(r => r.data);

// =================== DAG ===================

export const listDags = (projectId?: number) => http.get<Dag[]>('/engineering/dev/dags', {params: {projectId}}).then(r => r.data);
export const getDag = (id: number) => http.get<Dag>(`/engineering/dev/dags/${id}`).then(r => r.data);
export const createDag = (data: Dag) => http.post<Dag>('/engineering/dev/dags', data).then(r => r.data);
export const updateDag = (id: number, data: Dag) => http.put<Dag>(`/engineering/dev/dags/${id}`, data).then(r => r.data);
export const deleteDag = (id: number) => http.delete<void>(`/engineering/dev/dags/${id}`).then(r => r.data);

export const triggerDag = (id: number) => http.post<DagExecution>(`/engineering/dev/dags/${id}/trigger`).then(r => r.data);
export const stopDag = (id: number, executionId: number) =>
    http.post<void>(`/engineering/dev/dags/${id}/executions/${executionId}/stop`).then(r => r.data);
export const listDagExecutions = (id: number) => http.get<DagExecution[]>(`/engineering/dev/dags/${id}/executions`).then(r => r.data);
