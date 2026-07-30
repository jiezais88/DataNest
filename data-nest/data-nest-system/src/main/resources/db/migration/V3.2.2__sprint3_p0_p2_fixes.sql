-- ============================================
-- V3.2.2__sprint3_p0_p2_fixes.sql
-- Sprint 3 code review 修复（一次到位）
-- 1) dag_project 加 ds_project_code（P0-3：DagProject 真正映射到 DS 项目）
-- 2) node_execution 加 sync_job_id（P1-2：SYNC 节点追踪 async XXL-JOB）
-- 3) dag_execution 加 PG 部分唯一索引（P1-3：同一 DAG 同时只允许一个 RUNNING）
-- 4) node_execution 加 status 乐观锁字段（性能7：防止 sync 并发覆盖）
-- ============================================

-- 1) dag_project.ds_project_code
ALTER TABLE dag_project
    ADD COLUMN IF NOT EXISTS ds_project_code BIGINT DEFAULT NULL;

COMMENT
ON COLUMN dag_project.ds_project_code IS 'DS 项目 code（关联 DolphinScheduler t_ds_project.code）';

CREATE INDEX IF NOT EXISTS idx_dag_project_ds_project_code
    ON dag_project (ds_project_code);

-- 2) node_execution.sync_job_id
ALTER TABLE node_execution
    ADD COLUMN IF NOT EXISTS sync_job_id BIGINT DEFAULT NULL;

COMMENT
ON COLUMN node_execution.sync_job_id IS '关联的同步任务 ID（SYNC 节点专用；用于 DagExecutionSyncService 反查 sync_job_history 同步终态）';

CREATE INDEX IF NOT EXISTS idx_node_execution_sync_job_id
    ON node_execution (sync_job_id);

-- 3) dag_execution 部分唯一索引（同一 DAG 同一时刻只能有一个 RUNNING）
-- PG 部分索引：只在 status='RUNNING' 时加唯一约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_dag_execution_running
    ON dag_execution (dag_id)
    WHERE status = 'RUNNING';

-- 4) node_execution 乐观锁（防止 sync 并发覆盖同一行）
-- @version 字段由 MyBatis-Plus 管理；这里仅加列
ALTER TABLE node_execution
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
