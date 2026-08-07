-- V3.4.2__fix_collect_task_status_enum.sql
-- 修复 collect_task.status 的默认值与注释，与代码枚举 CollectTaskStatus 保持一致

-- 1. 修正默认值
ALTER TABLE collect_task
    ALTER COLUMN status SET DEFAULT 'NEVER_EXECUTED';

-- 2. 修正列注释
COMMENT ON COLUMN collect_task.status IS '任务状态：NEVER_EXECUTED 未执行，RUNNING 运行中，SUCCESS 成功，FAILED 失败，TERMINATED 已终止';

-- 3. 把历史遗留的非法状态统一刷新为 NEVER_EXECUTED（避免代码侧 fromCode 返回 null）
UPDATE collect_task
SET status = 'NEVER_EXECUTED'
WHERE status NOT IN ('NEVER_EXECUTED', 'RUNNING', 'SUCCESS', 'FAILED', 'TERMINATED');
