-- ============================================
-- V1.0.0__init_user_tables.sql
-- 初始化用户权限相关表
-- ============================================

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user
(
    id
    BIGINT
    NOT
    NULL
    PRIMARY
    KEY,
    username
    VARCHAR
(
    30
) NOT NULL,
    password VARCHAR
(
    255
) NOT NULL,
    email VARCHAR
(
    100
) DEFAULT NULL,
    phone VARCHAR
(
    20
) DEFAULT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_username ON sys_user(username);

COMMENT
ON TABLE  sys_user             IS '系统用户';
COMMENT
ON COLUMN sys_user.id          IS '主键ID (雪花算法)';
COMMENT
ON COLUMN sys_user.username    IS '用户名';
COMMENT
ON COLUMN sys_user.password    IS '密码 (BCrypt)';
COMMENT
ON COLUMN sys_user.email       IS '邮箱';
COMMENT
ON COLUMN sys_user.phone       IS '手机号';
COMMENT
ON COLUMN sys_user.enabled     IS '是否启用';
COMMENT
ON COLUMN sys_user.created_at  IS '创建时间';
COMMENT
ON COLUMN sys_user.updated_at  IS '更新时间';

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role
(
    id
    BIGINT
    NOT
    NULL
    PRIMARY
    KEY,
    code
    VARCHAR
(
    30
) NOT NULL,
    name VARCHAR
(
    50
) NOT NULL,
    description VARCHAR
(
    200
) DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_code ON sys_role(code);

COMMENT
ON TABLE  sys_role             IS '系统角色';
COMMENT
ON COLUMN sys_role.id          IS '主键ID';
COMMENT
ON COLUMN sys_role.code        IS '角色编码';
COMMENT
ON COLUMN sys_role.name        IS '角色名称';
COMMENT
ON COLUMN sys_role.description IS '角色描述';

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role
(
    id
    BIGINT
    NOT
    NULL
    PRIMARY
    KEY,
    user_id
    BIGINT
    NOT
    NULL,
    role_id
    BIGINT
    NOT
    NULL,
    created_at
    TIMESTAMP
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_role ON sys_user_role(user_id, role_id);

COMMENT
ON TABLE  sys_user_role         IS '用户角色关联';
COMMENT
ON COLUMN sys_user_role.user_id IS '用户ID';
COMMENT
ON COLUMN sys_user_role.role_id IS '角色ID';

-- 权限表
CREATE TABLE IF NOT EXISTS sys_permission
(
    id
    BIGINT
    NOT
    NULL
    PRIMARY
    KEY,
    code
    VARCHAR
(
    50
) NOT NULL,
    name VARCHAR
(
    100
) NOT NULL,
    description VARCHAR
(
    200
) DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_permission_code ON sys_permission(code);

COMMENT
ON TABLE  sys_permission             IS '系统权限';
COMMENT
ON COLUMN sys_permission.code        IS '权限编码';
COMMENT
ON COLUMN sys_permission.name        IS '权限名称';

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission
(
    id
    BIGINT
    NOT
    NULL
    PRIMARY
    KEY,
    role_id
    BIGINT
    NOT
    NULL,
    permission_id
    BIGINT
    NOT
    NULL,
    created_at
    TIMESTAMP
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_permission ON sys_role_permission(role_id, permission_id);

COMMENT
ON TABLE  sys_role_permission             IS '角色权限关联';
COMMENT
ON COLUMN sys_role_permission.role_id     IS '角色ID';
COMMENT
ON COLUMN sys_role_permission.permission_id IS '权限ID';
