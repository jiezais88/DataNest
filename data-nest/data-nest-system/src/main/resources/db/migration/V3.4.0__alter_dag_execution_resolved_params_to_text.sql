-- ============================================
-- V3.4.0__alter_dag_execution_resolved_params_to_text.sql
-- 修复 MyBatis-Plus 写 dag_execution.resolved_params 时
-- "column is of type jsonb but expression is of type character varying" 错误
-- 与 source_tables_detail、scope、allowed_types 等列保持一致：TEXT 存 JSON 字符串
-- ============================================

ALTER TABLE dag_execution
    ALTER COLUMN resolved_params TYPE TEXT USING resolved_params::TEXT;

COMMENT ON COLUMN dag_execution.resolved_params IS '本次执行解析后的参数值（手动覆盖 + 默认值 + 系统变量），JSON 字符串存储';
