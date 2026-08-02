-- ============================================
-- V3.4.1__add_sys_user_audit_columns.sql
-- 用户表增加创建人/修改人，用于用户管理及跨模块列表展示
-- ============================================

ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS created_by BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS updated_by BIGINT DEFAULT NULL;

COMMENT
ON COLUMN sys_user.created_by IS '创建人 (sys_user.id)';
COMMENT
ON COLUMN sys_user.updated_by IS '修改人 (sys_user.id)';
