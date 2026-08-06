-- ============================================
-- V2.0.1__init_governance.sql
-- 初始化元数据采集与元数据管理表
-- ============================================

-- --------------------------------------------
-- 采集任务
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS collect_task (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    datasource_id BIGINT NOT NULL,
    datasource_name VARCHAR(100) NOT NULL,
    scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    collect_mode VARCHAR(20) NOT NULL DEFAULT 'FULL',
    trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    cron_expression VARCHAR(100) DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    last_execute_time TIMESTAMP DEFAULT NULL,
    last_history_id BIGINT DEFAULT NULL,
    description TEXT DEFAULT NULL,
    xxl_job_id INT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_collect_task_name ON collect_task(name);
CREATE INDEX IF NOT EXISTS idx_collect_task_datasource_id ON collect_task(datasource_id);
CREATE INDEX IF NOT EXISTS idx_collect_task_status ON collect_task(status);

COMMENT ON TABLE collect_task IS '元数据采集任务';
COMMENT ON COLUMN collect_task.id IS '主键ID (雪花算法)';
COMMENT ON COLUMN collect_task.name IS '任务名称';
COMMENT ON COLUMN collect_task.datasource_id IS '关联数据源ID';
COMMENT ON COLUMN collect_task.datasource_name IS '数据源名称（冗余）';
COMMENT ON COLUMN collect_task.scope IS '采集范围：库/Schema 名称数组';
COMMENT ON COLUMN collect_task.collect_mode IS '采集模式：FULL 全量，FULL_INCREMENT 全量+增量';
COMMENT ON COLUMN collect_task.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN collect_task.cron_expression IS 'Cron 表达式，trigger_type=CRON 时必填';
COMMENT ON COLUMN collect_task.status IS '任务状态：NORMAL 正常，PAUSED 暂停，ERROR 异常';
COMMENT ON COLUMN collect_task.last_execute_time IS '最近执行时间';
COMMENT ON COLUMN collect_task.last_history_id IS '最近一次历史记录ID';
COMMENT ON COLUMN collect_task.description IS '任务描述';
COMMENT ON COLUMN collect_task.xxl_job_id IS 'XXL-JOB 注册任务 ID';
COMMENT ON COLUMN collect_task.created_by IS '创建人ID';
COMMENT ON COLUMN collect_task.updated_by IS '更新人ID';
COMMENT ON COLUMN collect_task.created_at IS '创建时间';
COMMENT ON COLUMN collect_task.updated_at IS '更新时间';

-- --------------------------------------------
-- 采集历史
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS collect_history (
    id BIGINT NOT NULL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    task_name VARCHAR(100) NOT NULL,
    datasource_id BIGINT NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    db_count INT NOT NULL DEFAULT 0,
    table_count INT NOT NULL DEFAULT 0,
    column_count INT NOT NULL DEFAULT 0,
    added_table_count INT NOT NULL DEFAULT 0,
    updated_table_count INT NOT NULL DEFAULT 0,
    deleted_table_count INT NOT NULL DEFAULT 0,
    added_column_count INT NOT NULL DEFAULT 0,
    updated_column_count INT NOT NULL DEFAULT 0,
    deleted_column_count INT NOT NULL DEFAULT 0,
    error_message TEXT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_collect_history_task_id ON collect_history(task_id);
CREATE INDEX IF NOT EXISTS idx_collect_history_status ON collect_history(status);
CREATE INDEX IF NOT EXISTS idx_collect_history_started_at ON collect_history(started_at);

COMMENT ON TABLE collect_history IS '元数据采集历史';
COMMENT ON COLUMN collect_history.id IS '主键ID';
COMMENT ON COLUMN collect_history.task_id IS '关联任务ID';
COMMENT ON COLUMN collect_history.task_name IS '任务名称（冗余）';
COMMENT ON COLUMN collect_history.datasource_id IS '关联数据源ID';
COMMENT ON COLUMN collect_history.trigger_type IS '触发方式：MANUAL/CRON';
COMMENT ON COLUMN collect_history.status IS '执行状态：RUNNING 运行中，SUCCESS 成功，FAILED 失败';
COMMENT ON COLUMN collect_history.started_at IS '开始时间';
COMMENT ON COLUMN collect_history.ended_at IS '结束时间';
COMMENT ON COLUMN collect_history.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN collect_history.db_count IS '采集库/Schema 数';
COMMENT ON COLUMN collect_history.table_count IS '采集表数';
COMMENT ON COLUMN collect_history.column_count IS '采集字段数';
COMMENT ON COLUMN collect_history.added_table_count IS '新增表数';
COMMENT ON COLUMN collect_history.updated_table_count IS '更新表数';
COMMENT ON COLUMN collect_history.deleted_table_count IS '删除表数';
COMMENT ON COLUMN collect_history.added_column_count IS '新增字段数';
COMMENT ON COLUMN collect_history.updated_column_count IS '更新字段数';
COMMENT ON COLUMN collect_history.deleted_column_count IS '删除字段数';
COMMENT ON COLUMN collect_history.error_message IS '错误信息';
COMMENT ON COLUMN collect_history.created_at IS '创建时间';

-- --------------------------------------------
-- 采集执行日志
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS collect_execution_log (
    id BIGINT NOT NULL PRIMARY KEY,
    history_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    level VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_collect_execution_log_history_id ON collect_execution_log(history_id);
CREATE INDEX IF NOT EXISTS idx_collect_execution_log_task_id ON collect_execution_log(task_id);
CREATE INDEX IF NOT EXISTS idx_collect_execution_log_created_at ON collect_execution_log(created_at);

COMMENT ON TABLE collect_execution_log IS '元数据采集执行日志';
COMMENT ON COLUMN collect_execution_log.id IS '主键ID';
COMMENT ON COLUMN collect_execution_log.history_id IS '关联历史记录ID';
COMMENT ON COLUMN collect_execution_log.task_id IS '关联任务ID';
COMMENT ON COLUMN collect_execution_log.level IS '日志级别：INFO/WARN/ERROR';
COMMENT ON COLUMN collect_execution_log.message IS '日志内容';
COMMENT ON COLUMN collect_execution_log.created_at IS '创建时间';

-- --------------------------------------------
-- 元数据表
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS metadata_table (
    id BIGINT NOT NULL PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    database_name VARCHAR(100) NOT NULL,
    schema_name VARCHAR(100) DEFAULT NULL,
    table_name VARCHAR(100) NOT NULL,
    table_comment TEXT DEFAULT NULL,
    manual_comment TEXT DEFAULT NULL,
    source_status VARCHAR(20) NOT NULL DEFAULT 'ONLINE',
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_metadata_table_unique ON metadata_table(datasource_id, database_name, COALESCE (schema_name, ''), table_name);
CREATE INDEX IF NOT EXISTS idx_metadata_table_datasource_id ON metadata_table(datasource_id);
CREATE INDEX IF NOT EXISTS idx_metadata_table_database_name ON metadata_table(database_name);

COMMENT ON TABLE metadata_table IS '元数据表';
COMMENT ON COLUMN metadata_table.id IS '主键ID';
COMMENT ON COLUMN metadata_table.datasource_id IS '关联数据源ID';
COMMENT ON COLUMN metadata_table.database_name IS '数据库名';
COMMENT ON COLUMN metadata_table.schema_name IS 'Schema名';
COMMENT ON COLUMN metadata_table.table_name IS '表名';
COMMENT ON COLUMN metadata_table.table_comment IS '源库表注释';
COMMENT ON COLUMN metadata_table.manual_comment IS '人工编辑的表注释，增量采集不覆盖';
COMMENT ON COLUMN metadata_table.source_status IS '源状态：ONLINE 在线，OFFLINE 源已删除';
COMMENT ON COLUMN metadata_table.created_by IS '创建人ID';
COMMENT ON COLUMN metadata_table.updated_by IS '更新人ID';
COMMENT ON COLUMN metadata_table.created_at IS '创建时间';
COMMENT ON COLUMN metadata_table.updated_at IS '更新时间';

-- --------------------------------------------
-- 元数据字段
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS metadata_column (
    id BIGINT NOT NULL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    column_name VARCHAR(100) NOT NULL,
    data_type VARCHAR(100) NOT NULL,
    column_comment TEXT DEFAULT NULL,
    manual_comment TEXT DEFAULT NULL,
    is_nullable BOOLEAN DEFAULT TRUE,
    column_default TEXT DEFAULT NULL,
    ordinal_position INT NOT NULL DEFAULT 0,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_metadata_column_unique ON metadata_column(table_id, column_name);
CREATE INDEX IF NOT EXISTS idx_metadata_column_table_id ON metadata_column(table_id);

COMMENT ON TABLE metadata_column IS '元数据字段';
COMMENT ON COLUMN metadata_column.id IS '主键ID';
COMMENT ON COLUMN metadata_column.table_id IS '关联元数据表ID';
COMMENT ON COLUMN metadata_column.column_name IS '字段名';
COMMENT ON COLUMN metadata_column.data_type IS '字段类型';
COMMENT ON COLUMN metadata_column.column_comment IS '源库字段注释';
COMMENT ON COLUMN metadata_column.manual_comment IS '人工编辑的字段注释，增量采集不覆盖';
COMMENT ON COLUMN metadata_column.is_nullable IS '是否允许为空';
COMMENT ON COLUMN metadata_column.column_default IS '默认值';
COMMENT ON COLUMN metadata_column.ordinal_position IS '字段顺序';
COMMENT ON COLUMN metadata_column.created_by IS '创建人ID';
COMMENT ON COLUMN metadata_column.updated_by IS '更新人ID';
COMMENT ON COLUMN metadata_column.created_at IS '创建时间';
COMMENT ON COLUMN metadata_column.updated_at IS '更新时间';
