-- ============================================
-- V1.0.1__seed_roles_and_admin.sql
-- 预置 4 个角色 + admin 账号
-- ============================================

-- 预置角色 (id 固定以便后续扩展)
INSERT INTO sys_role (id, code, name, description)
VALUES (1, 'SUPER_ADMIN', '超级管理员', '拥有系统所有权限'),
       (2, 'DATA_ENGINEER', '数据工程师', '负责数据集成和数据开发'),
       (3, 'DATA_ANALYST', '数据分析师', '数据查询与分析'),
       (4, 'GOVERNANCE_ADMIN', '治理管理员', '数据治理与质量管理') ON CONFLICT DO NOTHING;

-- 预置权限
INSERT INTO sys_permission (id, code, name, description)
VALUES (1, 'user:read', '查看用户', NULL),
       (2, 'user:create', '创建用户', NULL),
       (3, 'user:update', '编辑用户', NULL),
       (4, 'user:delete', '删除用户', NULL),
       (5, 'user:reset_password', '重置密码', NULL),
       (6, 'user:toggle_status', '启用/禁用用户', NULL),
       (7, 'system:admin', '系统管理', NULL),
       (8, 'data:read', '数据读取', NULL),
       (9, 'data:write', '数据写入', NULL),
       (10, 'pipeline:manage', '任务管理', NULL),
       (11, 'query:execute', '查询执行', NULL),
       (12, 'governance:manage', '治理管理', NULL),
       (13, 'quality:manage', '质量管理', NULL) ON CONFLICT DO NOTHING;

-- 超级管理员 -> 全部权限
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT id, 1, id
FROM sys_permission ON CONFLICT DO NOTHING;

-- 数据工程师
INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES (100, 2, 1),
       (101, 2, 8),
       (102, 2, 9),
       (103, 2, 10) ON CONFLICT DO NOTHING;

-- 数据分析师
INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES (200, 3, 1),
       (201, 3, 8),
       (202, 3, 11) ON CONFLICT DO NOTHING;

-- 治理管理员
INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES (300, 4, 1),
       (301, 4, 8),
       (302, 4, 12),
       (303, 4, 13) ON CONFLICT DO NOTHING;

-- 预置管理员账号 (admin / admin123)
INSERT INTO sys_user (id, username, password, email, enabled)
VALUES (1, 'admin',
        '$2b$12$5Hsog/ML5QbrAPivCqsCeuLJpItjSvMS7HPqtWwOy53DgX8mE1Deu',
        'admin@datanest.io', TRUE) ON CONFLICT DO NOTHING;

-- admin 关联超级管理员
INSERT INTO sys_user_role (id, user_id, role_id)
VALUES (1, 1, 1) ON CONFLICT DO NOTHING;
