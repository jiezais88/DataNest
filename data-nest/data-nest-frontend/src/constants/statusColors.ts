/**
 * DAG 节点执行状态的颜色与中文标签（Editor 画布、全局执行历史迷你 DAG 共用）。
 * 色值唯一来源是 tokens.css 的 --color-node-* 变量（tailwind ds-node-* 同样引用），
 * 这里以 var() 表达式导出给内联样式（节点左边框、状态文字、迷你 DAG 图例）。
 */

export const NODE_STATUS_COLOR: Record<string, string> = {
    SUCCESS: 'rgb(var(--color-node-success))',
    FAILED: 'rgb(var(--color-node-failed))',
    TERMINATED: 'rgb(var(--color-node-failed))',
    RUNNING: 'rgb(var(--color-node-running))',
    WAITING: 'rgb(var(--color-node-waiting))',
    SKIPPED: 'rgb(var(--color-node-skipped))',
};

export const NODE_STATUS_LABEL: Record<string, string> = {
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    TERMINATED: '已终止',
    WAITING: '等待中',
    SKIPPED: '已跳过',
};
