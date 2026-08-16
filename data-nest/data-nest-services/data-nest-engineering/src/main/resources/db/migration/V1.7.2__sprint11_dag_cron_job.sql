-- Sprint 11 F3 方案 A：cron 触发纳入队列容量判定。
-- dag 增加 scheduler_job_id：PowerJob 侧独立 cron job ID（方案 A 后 DAG 的 cron 不再挂在 workflow 上，
-- 改为 job 侧 cron job 到点调 /internal/dag/scheduled-trigger 做队列容量判定后触发）。
ALTER TABLE dag ADD COLUMN IF NOT EXISTS scheduler_job_id BIGINT;
COMMENT ON COLUMN dag.scheduler_job_id IS 'job 侧 DAG cron job ID（方案 A，cron 触发纳入队列）';
