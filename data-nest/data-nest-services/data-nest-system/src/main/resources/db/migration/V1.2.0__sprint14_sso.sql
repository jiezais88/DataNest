-- ============================================
-- V1.2.0__sprint14_sso.sql
-- Sprint 14 SSO + 认证安全：
--   1) sys_user 扩展 5 列（auth_source/sso_subject/password_expire_at/login_fail_count/locked_until）
--   2) sso_subject 部分唯一索引（NULL 不冲突；PostgreSQL WHERE 索引）
--   3) 权限点种子：auth:config(188) / auth:sync(189)，仅绑 SUPER_ADMIN
-- 注意：紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配；
--       auth_source 存量回填 LOCAL（DEFAULT），行为不变。
-- ============================================

ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS auth_source character varying(16) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS sso_subject character varying(128);
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS password_expire_at timestamp without time zone;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS login_fail_count integer NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS locked_until timestamp without time zone;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_sso_subject ON sys_user (sso_subject) WHERE sso_subject IS NOT NULL;

COMMENT ON COLUMN sys_user.auth_source IS '认证来源（LOCAL/OIDC/LDAP，Sprint 14 SSO）';
COMMENT ON COLUMN sys_user.sso_subject IS 'IdP 唯一标识（OIDC sub / LDAP dn），部分唯一索引';
COMMENT ON COLUMN sys_user.password_expire_at IS '密码过期时间（仅 LOCAL 用户）';
COMMENT ON COLUMN sys_user.login_fail_count IS '连续登录失败次数（仅 LOCAL 用户）';
COMMENT ON COLUMN sys_user.locked_until IS '登录锁定截止时间（仅 LOCAL 用户）';

INSERT INTO sys_permission (id, code, name, description) VALUES (188, 'auth:config', '配置', '身份认证：SSO/LDAP 配置、登录模式与密码策略') ON CONFLICT DO NOTHING;
INSERT INTO sys_permission (id, code, name, description) VALUES (189, 'auth:sync', '同步用户', '身份认证：LDAP 用户同步') ON CONFLICT DO NOTHING;
INSERT INTO sys_role_permission (id, role_id, permission_id, created_at) SELECT 5000000 + row_number() OVER (), r.id, p.id, CURRENT_TIMESTAMP FROM sys_role r CROSS JOIN sys_permission p WHERE r.code = 'SUPER_ADMIN' AND p.code IN ('auth:config','auth:sync') ON CONFLICT DO NOTHING;
