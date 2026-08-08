// Sprint 7 F2：任务模板库 API（engineering TaskTemplateController，/engineering/task-templates/**）
// 全部端点仅超管/工程师（后端 @SaCheckRole 兜底）。
import request from './request';
import type {Result} from '../types/common';
import type {
    CreateTaskResult,
    TaskTemplate,
    TaskTemplateSaveRequest,
    TemplateCreateTaskRequest,
} from '../types/taskTemplate';

/** 模板列表（全量返回，类型/来源过滤由前端做，量小） */
export const listTaskTemplates = () =>
    request.get<Result<TaskTemplate[]>>('/engineering/task-templates').then(r => r.data);

export const createTaskTemplate = (data: TaskTemplateSaveRequest) =>
    request.post<Result<TaskTemplate>>('/engineering/task-templates', data).then(r => r.data);

/** 编辑：configTemplate/sourceTaskId 缺省 = 仅改名称/说明，保留原配置（后端语义） */
export const updateTaskTemplate = (id: string, data: TaskTemplateSaveRequest) =>
    request.put<Result<TaskTemplate>>(`/engineering/task-templates/${id}`, data).then(r => r.data);

/** 删除（内置模板 7304 禁删；快照式，不影响已创建任务） */
export const deleteTaskTemplate = (id: string) =>
    request.delete<Result<null>>(`/engineering/task-templates/${id}`).then(r => r.data);

/** 一键创建任务（SYNC 本地落 sync_job / COLLECT 远程落 collect_task） */
export const createTaskFromTemplate = (id: string, data: TemplateCreateTaskRequest) =>
    request.post<Result<CreateTaskResult>>(`/engineering/task-templates/${id}/create-task`, data).then(r => r.data);
