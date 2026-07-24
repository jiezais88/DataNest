-- ============================================
-- V2.0.0__init_datasource_connection.sql
-- 初始化数据源连接表
-- ============================================

CREATE TABLE IF NOT EXISTS datasource_connection
(
    id
    BIGINT
    NOT
    NULL
    PRIMARY
    KEY,
    name
    VARCHAR
(
    100
) NOT NULL,
    type VARCHAR
(
    20
) NOT NULL,
    host VARCHAR
(
    255
) NOT NULL,
    port INT NOT NULL,
    database_name VARCHAR
(
    100
) NOT NULL,
    schema_name VARCHAR
(
    100
) DEFAULT NULL,
    username VARCHAR
(
    100
) NOT NULL,
    encrypted_password TEXT NOT NULL,
    description TEXT DEFAULT NULL,
    status VARCHAR
(
    20
) NOT NULL DEFAULT 'NORMAL',
    last_test_time TIMESTAMP DEFAULT NULL,
    error_message TEXT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_datasource_connection_name ON datasource_connection(name);

COMMENT
ON TABLE datasource_connection IS '数据源连接信息';
COMMENT
ON COLUMN datasource_connection.id IS '主键ID (雪花算法)';
COMMENT
ON COLUMN datasource_connection.name IS '数据源名称';
COMMENT
ON COLUMN datasource_connection.type IS '数据源类型：MYSQL、POSTGRESQL、DORIS';
COMMENT
ON COLUMN datasource_connection.host IS '主机地址';
COMMENT
ON COLUMN datasource_connection.port IS '端口';
COMMENT
ON COLUMN datasource_connection.database_name IS '数据库名';
COMMENT
ON COLUMN datasource_connection.schema_name IS 'Schema名（PostgreSQL必填）';
COMMENT
ON COLUMN datasource_connection.username IS '用户名';
COMMENT
ON COLUMN datasource_connection.encrypted_password IS 'AES-256-GCM加密后的密码';
COMMENT
ON COLUMN datasource_connection.description IS '描述';
COMMENT
ON COLUMN datasource_connection.status IS '连接状态：NORMAL 正常，ERROR 连接失败，OFFLINE 已删除仍有引用';
COMMENT
ON COLUMN datasource_connection.last_test_time IS '最近测试时间';
COMMENT
ON COLUMN datasource_connection.error_message IS '最近一次错误信息';
COMMENT
ON COLUMN datasource_connection.created_by IS '创建人ID';
COMMENT
ON COLUMN datasource_connection.updated_by IS '更新人ID';
COMMENT
ON COLUMN datasource_connection.created_at IS '创建时间';
COMMENT
ON COLUMN datasource_connection.updated_at IS '更新时间';
