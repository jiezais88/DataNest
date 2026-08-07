-- ============================================
-- V3.2.1__sync_job_multitable_and_ratelimit.sql
-- Sprint 3：sync_job 增强 - 多表源 + 同步速率限流
-- 新增字段:
--   - source_tables_detail JSONB   (多表结构化配置，保留 source_tables JSONB 兼容)
--   - read_rate_limit_mbps INT     (读取速率限制 MB/s，0=不限)
--   - write_rate_limit_rows_per_second INT (写入速率限制 行/s，0=不限)
--   - rate_limit_enabled SMALLINT  (总开关：0-关闭 1-启用)
-- 决策: 实施计划里写的 sync_task 是文档笔误，实际表名是 sync_job
-- ============================================

-- --------------------------------------------
-- sync_job 主表增强：多表结构化配置
-- --------------------------------------------
-- source_tables 已存在（V3.0.0：JSONB 数组），新增 source_tables_detail 用于结构化多表配置
-- 形态: [{"tableName":"t1","incrementalField":"id","lastSyncTime":"2026-07-30 12:00:00"}, ...]
ALTER TABLE sync_job
    ADD COLUMN IF NOT EXISTS source_tables_detail JSONB NOT NULL DEFAULT '[]'::jsonb;

-- --------------------------------------------
-- sync_job 主表增强：同步速率限流
-- --------------------------------------------
ALTER TABLE sync_job
    ADD COLUMN IF NOT EXISTS read_rate_limit_mbps INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS write_rate_limit_rows_per_second INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rate_limit_enabled SMALLINT NOT NULL DEFAULT 0;

-- --------------------------------------------
-- 列注释
-- --------------------------------------------
COMMENT ON COLUMN sync_job.source_tables_detail IS '多表结构化配置 JSONB（比 source_tables 字段多 incrementalField / lastSyncTime 等）';
COMMENT ON COLUMN sync_job.read_rate_limit_mbps IS '读取速率限制（MB/s，0=不限制）。Sprint 3 增强：保护源库 IO';
COMMENT ON COLUMN sync_job.write_rate_limit_rows_per_second IS '写入速率限制（行/秒，0=不限制）。Sprint 3 增强：保护目标库 IO';
COMMENT ON COLUMN sync_job.rate_limit_enabled IS '限流总开关：0-关闭（按 read/write 字段生效），1-启用';

-- --------------------------------------------
-- 索引：加速「按 read_rate_limit 过滤」
-- --------------------------------------------
CREATE INDEX IF NOT EXISTS idx_sync_job_rate_limit_enabled
    ON sync_job (rate_limit_enabled)
    WHERE rate_limit_enabled = 1;
