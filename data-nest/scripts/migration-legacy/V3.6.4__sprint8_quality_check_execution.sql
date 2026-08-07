-- ============================================
-- V3.6.4__sprint8_quality_check_execution.sql
-- Sprint 8：质量检查执行 + 结果记录（执行层）
-- 说明：
--   1) quality_job 增加 xxl_job_id（每任务独立注册 XXL-JOB 到 worker 组，定时调度用）
--   2) 新增 quality_check_batch 批次表（一次任务/单规则执行）
--   3) 新增 quality_check_detail 规则明细表（每条规则的执行结果）
-- 注意：本脚本采用紧凑单行 SQL 写法，规避被格式化工具拆行导致的问题。
-- ============================================

-- 1) quality_job 增加 xxl_job_id
ALTER TABLE quality_job ADD COLUMN IF NOT EXISTS xxl_job_id INTEGER;
COMMENT ON COLUMN quality_job.xxl_job_id IS '定时调度 XXL-JOB 任务 ID（注册到 data-nest-worker 组，带自身 cron）';

-- 2) quality_check_batch 批次表
CREATE TABLE IF NOT EXISTS quality_check_batch (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT,
    job_name VARCHAR(100),
    trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_quality_check_batch_job_id ON quality_check_batch (job_id);
CREATE INDEX IF NOT EXISTS idx_quality_check_batch_status ON quality_check_batch (status);

COMMENT ON TABLE quality_check_batch IS '质量检查批次（Sprint 8 执行层）';
COMMENT ON COLUMN quality_check_batch.job_id IS '质量任务 ID（单规则执行为空）';
COMMENT ON COLUMN quality_check_batch.job_name IS '任务名称快照';
COMMENT ON COLUMN quality_check_batch.trigger_type IS '触发方式：MANUAL / SCHEDULED / AUTO_TRIGGER';
COMMENT ON COLUMN quality_check_batch.status IS '批次状态：RUNNING / SUCCESS / PARTIAL_FAILED / FAILED';
COMMENT ON COLUMN quality_check_batch.started_at IS '开始时间';
COMMENT ON COLUMN quality_check_batch.ended_at IS '结束时间';
COMMENT ON COLUMN quality_check_batch.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN quality_check_batch.error_message IS '整体错误信息（非规则级）';

-- 3) quality_check_detail 规则明细表
CREATE TABLE IF NOT EXISTS quality_check_detail (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    rule_name VARCHAR(100),
    rule_type VARCHAR(20),
    table_id BIGINT,
    result_metric VARCHAR(50),
    result_value DECIMAL(20,6),
    success SMALLINT NOT NULL DEFAULT 0,
    error_message TEXT,
    executed_sql TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_quality_check_detail_batch_id ON quality_check_detail (batch_id);
CREATE INDEX IF NOT EXISTS idx_quality_check_detail_rule_id ON quality_check_detail (rule_id);

COMMENT ON TABLE quality_check_detail IS '质量检查规则明细（Sprint 8 执行层）';
COMMENT ON COLUMN quality_check_detail.batch_id IS '所属批次';
COMMENT ON COLUMN quality_check_detail.rule_id IS '质量规则 ID';
COMMENT ON COLUMN quality_check_detail.rule_name IS '规则名称快照';
COMMENT ON COLUMN quality_check_detail.rule_type IS '规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL';
COMMENT ON COLUMN quality_check_detail.table_id IS '目标表 metadata_table.id';
COMMENT ON COLUMN quality_check_detail.result_metric IS '结果指标名';
COMMENT ON COLUMN quality_check_detail.result_value IS '执行结果值（DECIMAL，仅记录结果值不做分级）';
COMMENT ON COLUMN quality_check_detail.success IS '规则执行是否成功：1 成功，0 失败';
COMMENT ON COLUMN quality_check_detail.error_message IS '规则执行错误信息';
COMMENT ON COLUMN quality_check_detail.executed_sql IS '实际执行的校验 SQL';
