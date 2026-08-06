-- ============================================
-- V3.3.8__dag_execution_params.sql
-- Sprint 4：DAG 执行实例存储解析后的参数值
-- ============================================

ALTER TABLE dag_execution
    ADD COLUMN IF NOT EXISTS resolved_params JSONB DEFAULT '{}';

COMMENT ON COLUMN dag_execution.resolved_params IS '本次执行解析后的参数值（手动覆盖 + 默认值 + 系统变量）';
