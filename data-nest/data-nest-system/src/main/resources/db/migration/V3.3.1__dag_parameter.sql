-- ============================================
-- V3.3.1__dag_parameter.sql
-- Sprint 4：DAG 自定义参数表
-- ============================================

CREATE TABLE IF NOT EXISTS dag_parameter
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    dag_id
    BIGINT
    NOT
    NULL,
    param_name
    VARCHAR
(
    64
) NOT NULL,
    param_type VARCHAR
(
    16
) NOT NULL CHECK
(
    param_type
    IN
(
    'STRING',
    'NUMBER',
    'DATE',
    'BOOLEAN'
)),
    default_value VARCHAR
(
    255
),
    required SMALLINT NOT NULL DEFAULT 1,
    description VARCHAR
(
    500
),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_param_name UNIQUE
(
    dag_id,
    param_name
)
    );

COMMENT
ON TABLE dag_parameter IS 'DAG 自定义参数';
COMMENT
ON COLUMN dag_parameter.param_name IS '参数名，DAG 内唯一';
COMMENT
ON COLUMN dag_parameter.param_type IS '参数类型：STRING / NUMBER / DATE / BOOLEAN';
COMMENT
ON COLUMN dag_parameter.default_value IS '默认值';
COMMENT
ON COLUMN dag_parameter.required IS '手动触发时是否必填：1 必填，0 可选';
