-- ============================================
-- V2.0.4__add_collect_change_detail.sql
-- 新增采集变更明细表 + 更新采集任务状态注释
-- ============================================

-- 创建变更明细表
CREATE TABLE IF NOT EXISTS collect_change_detail (
    id BIGINT NOT NULL PRIMARY KEY,
    history_id BIGINT NOT NULL,
    change_type VARCHAR(30) NOT NULL,
    database_name VARCHAR(100),
    schema_name VARCHAR(100),
    table_name VARCHAR(200),
    column_name VARCHAR(200),
    old_value TEXT,
    new_value TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE collect_change_detail IS '采集变更明细表';
COMMENT ON COLUMN collect_change_detail.history_id IS '关联的采集历史ID';
COMMENT ON COLUMN collect_change_detail.change_type IS '变更类型：ADDED_TABLE/DELETED_TABLE/MODIFIED_TABLE';
COMMENT ON COLUMN collect_change_detail.database_name IS '数据库名';
COMMENT ON COLUMN collect_change_detail.schema_name IS 'Schema名';
COMMENT ON COLUMN collect_change_detail.table_name IS '表名';
COMMENT ON COLUMN collect_change_detail.column_name IS '字段名（表级变更时为空）';
COMMENT ON COLUMN collect_change_detail.old_value IS '旧值';
COMMENT ON COLUMN collect_change_detail.new_value IS '新值';

CREATE INDEX IF NOT EXISTS idx_change_detail_history ON collect_change_detail(history_id);
