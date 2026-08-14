-- ============================================
-- V1.1.0__sprint11_audit.sql
-- Sprint 11 F1 审计日志（CM-05/CM-06）：通用审计表 audit_log
-- 范围：10 类操作留痕，90 天保留（job 定时清理 + system internal 兜底）
-- 约束：只增不改不删（无 update/delete 接口）；不记密码/Key 明文/查询结果数据
-- 注意：紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配；
--       id 用雪花算法（MyBatis-Plus ASSIGN_ID），无序列；
--       operator_name 冗余存储用户名（列表页免联表），落库时由 system 统一回填。
-- ============================================

CREATE TABLE IF NOT EXISTS audit_log (
    id bigint NOT NULL,
    operator_id bigint,
    operator_name character varying(64),
    op_type character varying(32) NOT NULL,
    resource_type character varying(32) NOT NULL,
    resource_id character varying(64),
    resource_name character varying(256),
    content text,
    result character varying(16) NOT NULL,
    error_message character varying(512),
    client_ip character varying(64),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_audit_log_operator ON audit_log (operator_name);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource ON audit_log (resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_type ON audit_log (op_type);

COMMENT ON TABLE audit_log IS '通用审计日志（Sprint 11 F1，只增不改不删，仅超管可查）';
COMMENT ON COLUMN audit_log.operator_id IS '操作人用户 ID';
COMMENT ON COLUMN audit_log.operator_name IS '操作人用户名（冗余存储，免联表）';
COMMENT ON COLUMN audit_log.op_type IS '操作类型（CREATE/UPDATE/DELETE/EXECUTE/CHANGE_LEVEL 等）';
COMMENT ON COLUMN audit_log.resource_type IS '资源类型（USER/DATASOURCE/SYNC_JOB/DAG/SQL_QUERY 等）';
COMMENT ON COLUMN audit_log.resource_id IS '资源 ID';
COMMENT ON COLUMN audit_log.resource_name IS '资源可读名称';
COMMENT ON COLUMN audit_log.content IS '操作内容摘要（SQL 前 200 字符 + 行数 + 耗时）';
COMMENT ON COLUMN audit_log.result IS '操作结果（SUCCESS/FAILURE）';
COMMENT ON COLUMN audit_log.error_message IS '失败原因摘要（脱敏）';
COMMENT ON COLUMN audit_log.client_ip IS '客户端 IP';
COMMENT ON COLUMN audit_log.created_at IS '操作时间';
