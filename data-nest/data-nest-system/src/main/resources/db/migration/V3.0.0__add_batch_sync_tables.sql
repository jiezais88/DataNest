-- ============================================
-- V3.0.0__add_batch_sync_tables.sql
-- 批量数据同步任务相关表
-- ============================================

-- --------------------------------------------
-- 同步任务主表
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS sync_job (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    source_datasource_id BIGINT NOT NULL,
    target_datasource_id BIGINT NOT NULL,
    source_database VARCHAR(100) DEFAULT NULL,
    source_schema VARCHAR(100) DEFAULT NULL,
    source_tables JSONB NOT NULL DEFAULT '[]'::jsonb,
    sync_mode VARCHAR(20) NOT NULL DEFAULT 'FULL',
    trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    cron_expression VARCHAR(100) DEFAULT NULL,
    retry_times INT NOT NULL DEFAULT 0,
    retry_interval INT NOT NULL DEFAULT 0,
    field_mapping JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    schedule_enabled SMALLINT NOT NULL DEFAULT 0,
    xxl_job_id INT DEFAULT NULL,
    description TEXT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_job_name ON sync_job(name);
CREATE INDEX IF NOT EXISTS idx_sync_job_source_datasource_id ON sync_job(source_datasource_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_target_datasource_id ON sync_job(target_datasource_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_status ON sync_job(status);

COMMENT ON TABLE sync_job IS '批量数据同步任务';
COMMENT ON COLUMN sync_job.id IS '主键ID';
COMMENT ON COLUMN sync_job.name IS '任务名称';
COMMENT ON COLUMN sync_job.source_datasource_id IS '源数据源ID';
COMMENT ON COLUMN sync_job.target_datasource_id IS '目标数据源ID（内置Doris）';
COMMENT ON COLUMN sync_job.source_database IS '源数据库名';
COMMENT ON COLUMN sync_job.source_schema IS '源Schema名';
COMMENT ON COLUMN sync_job.source_tables IS '源表名数组';
COMMENT ON COLUMN sync_job.sync_mode IS '同步模式：FULL 全量，INCREMENTAL 增量';
COMMENT ON COLUMN sync_job.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN sync_job.cron_expression IS 'Cron表达式';
COMMENT ON COLUMN sync_job.retry_times IS '失败重试次数（0-3）';
COMMENT ON COLUMN sync_job.retry_interval IS '重试间隔分钟数（1-30）';
COMMENT ON COLUMN sync_job.field_mapping IS '字段映射配置JSON';
COMMENT ON COLUMN sync_job.status IS '任务状态：NORMAL 正常，PAUSED 暂停，ERROR 异常';
COMMENT ON COLUMN sync_job.schedule_enabled IS '调度是否启用（0-停止 1-运行）';
COMMENT ON COLUMN sync_job.xxl_job_id IS 'XXL-JOB 注册任务ID';
COMMENT ON COLUMN sync_job.description IS '任务描述';
COMMENT ON COLUMN sync_job.created_by IS '创建人ID';
COMMENT ON COLUMN sync_job.updated_by IS '更新人ID';
COMMENT ON COLUMN sync_job.created_at IS '创建时间';
COMMENT ON COLUMN sync_job.updated_at IS '更新时间';

-- --------------------------------------------
-- 同步任务执行历史
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS sync_job_history (
    id BIGINT NOT NULL PRIMARY KEY,
    sync_job_id BIGINT NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    start_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMP DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    source_rows BIGINT DEFAULT 0,
    target_rows BIGINT DEFAULT 0,
    error_message TEXT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sync_job_history_sync_job_id ON sync_job_history(sync_job_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_history_status ON sync_job_history(status);
CREATE INDEX IF NOT EXISTS idx_sync_job_history_start_time ON sync_job_history(start_time);

COMMENT ON TABLE sync_job_history IS '批量数据同步执行历史';
COMMENT ON COLUMN sync_job_history.id IS '主键ID';
COMMENT ON COLUMN sync_job_history.sync_job_id IS '关联同步任务ID';
COMMENT ON COLUMN sync_job_history.trigger_type IS '触发方式：MANUAL/CRON';
COMMENT ON COLUMN sync_job_history.status IS '执行状态：RUNNING 运行中，SUCCESS 成功，FAILED 失败';
COMMENT ON COLUMN sync_job_history.start_time IS '开始时间';
COMMENT ON COLUMN sync_job_history.end_time IS '结束时间';
COMMENT ON COLUMN sync_job_history.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN sync_job_history.source_rows IS '源表读取行数';
COMMENT ON COLUMN sync_job_history.target_rows IS '目标表写入行数';
COMMENT ON COLUMN sync_job_history.error_message IS '错误信息';
COMMENT ON COLUMN sync_job_history.created_at IS '创建时间';

-- --------------------------------------------
-- 同步任务执行日志（Addax 日志片段）
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS sync_job_log (
    id BIGINT NOT NULL PRIMARY KEY,
    history_id BIGINT NOT NULL,
    sync_job_id BIGINT NOT NULL,
    level VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sync_job_log_history_id ON sync_job_log(history_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_log_sync_job_id ON sync_job_log(sync_job_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_log_created_at ON sync_job_log(created_at);

COMMENT ON TABLE sync_job_log IS '批量数据同步执行日志（Addax输出）';
COMMENT ON COLUMN sync_job_log.id IS '主键ID';
COMMENT ON COLUMN sync_job_log.history_id IS '关联执行历史ID';
COMMENT ON COLUMN sync_job_log.sync_job_id IS '关联同步任务ID';
COMMENT ON COLUMN sync_job_log.level IS '日志级别：INFO/WARN/ERROR';
COMMENT ON COLUMN sync_job_log.message IS '日志内容';
COMMENT ON COLUMN sync_job_log.created_at IS '创建时间';
