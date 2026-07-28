-- ============================================
-- V3.0.5__compliance_violation_type.sql
-- 合规检查结果扩展：范围字段、违规类型、标准/对象路径展示
-- ============================================

ALTER TABLE compliance_check_result
    ALTER COLUMN standard_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS datasource_id BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS database_name VARCHAR(255) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS schema_name VARCHAR(255) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS violation_type VARCHAR(20) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS standard_name VARCHAR(100) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS object_path VARCHAR(500) DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_compliance_check_result_datasource_id ON compliance_check_result(datasource_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_database_name ON compliance_check_result(database_name);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_schema_name ON compliance_check_result(schema_name);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_violation_type ON compliance_check_result(violation_type);

COMMENT
ON COLUMN compliance_check_result.standard_id IS '命中的命名规范ID，未命中时可为空';
COMMENT
ON COLUMN compliance_check_result.datasource_id IS '数据源ID';
COMMENT
ON COLUMN compliance_check_result.database_name IS '数据库名';
COMMENT
ON COLUMN compliance_check_result.schema_name IS 'Schema名';
COMMENT
ON COLUMN compliance_check_result.violation_type IS '违规类型：NAMING 命名不合规，TYPE 字段类型不合规';
COMMENT
ON COLUMN compliance_check_result.standard_name IS '命中的命名规范名称，未命中时可为空';
COMMENT
ON COLUMN compliance_check_result.object_path IS '检查对象路径，如 db.schema.table.column';
