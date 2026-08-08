// Sprint 7 F2：任务模板库类型（对齐 engineering TaskTemplateController DTO，DD-09）
// 类型范围经用户确认收敛为 SYNC + COLLECT（SQL 无独立任务实体、EXPORT 平台不存在，本期不做）。

export type TaskTemplateType = 'SYNC' | 'COLLECT';

export const TASK_TEMPLATE_TYPE_LABEL: Record<TaskTemplateType, string> = {
    SYNC: '同步任务',
    COLLECT: '采集任务',
};

/** config_template JSON 中的占位符定义（B4 定稿结构） */
export interface TemplatePlaceholder {
    key: string;
    label: string;
    required?: boolean;
    /** TEXT（缺省）/ DATASOURCE（数据源下拉，值为数据源 ID 字符串） */
    valueType?: 'TEXT' | 'DATASOURCE';
    defaultValue?: string;
}

export interface TaskTemplate {
    id: string;
    name: string;
    type: TaskTemplateType;
    /** BUILTIN=内置（禁删禁改，创建人展示「系统」）/ CUSTOM=自定义 */
    category?: string;
    description?: string;
    /** JSON 字符串：{"placeholders":[...],"config":{...}}，前端解析渲染占位符表单 */
    configTemplate?: string;
    /** 1 启用 / 0 停用（停用不可一键创建，后端 7307） */
    enabled?: number;
    createdBy?: string;
    /** 内置模板为 null，前端展示「系统」 */
    createdByName?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface TaskTemplateSaveRequest {
    name: string;
    type: TaskTemplateType;
    description?: string;
    /** 模板 JSON 原文（与 sourceTaskId 二选一） */
    configTemplate?: string;
    /** 从既有任务另存为模板：SYNC→sync_job.id / COLLECT→collect_task.id */
    sourceTaskId?: string;
}

export interface TemplateCreateTaskRequest {
    name: string;
    /** 占位符取值：key → 填写值（DATASOURCE 类型为数据源 ID 字符串） */
    values?: Record<string, string>;
}

export interface CreateTaskResult {
    taskType: TaskTemplateType;
    taskId: string;
}
