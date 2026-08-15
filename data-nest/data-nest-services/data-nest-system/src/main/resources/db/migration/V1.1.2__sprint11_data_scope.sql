-- ============================================
-- V1.1.2__sprint11_data_scope.sql
-- Sprint 11 F2 权限配置页「默认范围」显式化：
--   sys_role 新增 data_scope 字段（FULL=全部数据可见 / WHITELIST=仅授权数据可见）。
--   默认 FULL 保持向后兼容（旧角色无白名单 = 全量可见）。
-- 语义：用户数据权限合并时，任一角色 data_scope=FULL 即全量放行；
--       否则合并各 WHITELIST 角色的白名单（空白名单 = 什么都不可见）。
-- ============================================
ALTER TABLE sys_role ADD COLUMN data_scope VARCHAR(16) NOT NULL DEFAULT 'FULL';
