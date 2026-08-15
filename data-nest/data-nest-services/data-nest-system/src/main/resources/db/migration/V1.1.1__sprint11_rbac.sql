-- ============================================
-- V1.1.1__sprint11_rbac.sql
-- Sprint 11 F2 RBAC 细粒度权限（CM-02/03/04）：
--   0) 清理 Sprint 0 baseline 占位权限点/角色关联（旧 13 权限点 code 风格 user:read/data:read，
--      从未被功能使用——此前权限均为 @SaCheckRole 硬编码，无 @SaCheckPermission 消费）
--   1) sys_permission 种子：18 模块按钮级权限点 + 系统管理类权限点（共 88 个）
--   2) sys_role_permission 预置 4 角色权限点关联（PRD §6.2.1 按钮级矩阵）
--   3) sys_data_permission 三级数据权限白名单表（默认全量可见=无记录）
-- 注意：紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配；
--       id 用固定整数（权限点 100~187、角色关联 1000000+），无序列；
--       权限点 code 与 common PermissionCode 常量、前端映射表三者对齐。
-- ============================================

DELETE FROM sys_role_permission;
DELETE FROM sys_permission;

INSERT INTO sys_permission (id, code, name, description) VALUES (100, 'datasource:view', '查看', '数据源管理：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (101, 'datasource:create', '新建', '数据源管理：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (102, 'datasource:update', '编辑', '数据源管理：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (103, 'datasource:delete', '删除', '数据源管理：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (104, 'datasource:test', '测试连接', '数据源管理：测试连接');
INSERT INTO sys_permission (id, code, name, description) VALUES (105, 'sync:view', '查看', '批量同步任务：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (106, 'sync:create', '新建', '批量同步任务：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (107, 'sync:update', '编辑', '批量同步任务：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (108, 'sync:delete', '删除', '批量同步任务：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (109, 'sync:execute', '启动·停止执行', '批量同步任务：启动·停止执行');
INSERT INTO sys_permission (id, code, name, description) VALUES (110, 'sync:history', '查看执行历史', '批量同步任务：查看执行历史');
INSERT INTO sys_permission (id, code, name, description) VALUES (111, 'cdc:view', '查看', 'CDC 管道：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (112, 'cdc:create', '新建', 'CDC 管道：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (113, 'cdc:update', '编辑', 'CDC 管道：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (114, 'cdc:delete', '删除', 'CDC 管道：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (115, 'cdc:execute', '启动·停止', 'CDC 管道：启动·停止');
INSERT INTO sys_permission (id, code, name, description) VALUES (116, 'cdc:monitor', '查看监控', 'CDC 管道：查看监控');
INSERT INTO sys_permission (id, code, name, description) VALUES (117, 'dag:view', '查看', 'DAG 编排：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (118, 'dag:create', '新建', 'DAG 编排：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (119, 'dag:update', '编辑', 'DAG 编排：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (120, 'dag:delete', '删除', 'DAG 编排：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (121, 'dag:execute', '手动触发', 'DAG 编排：手动触发');
INSERT INTO sys_permission (id, code, name, description) VALUES (122, 'dag:history', '查看执行历史', 'DAG 编排：查看执行历史');
INSERT INTO sys_permission (id, code, name, description) VALUES (123, 'template:view', '查看', '任务模板库：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (124, 'template:create', '新建', '任务模板库：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (125, 'template:update', '编辑', '任务模板库：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (126, 'template:delete', '删除', '任务模板库：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (127, 'metadata:view', '查看', '元数据：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (128, 'metadata:comment', '编辑注释', '元数据：编辑注释');
INSERT INTO sys_permission (id, code, name, description) VALUES (129, 'metadata:lineage', '查看血缘', '元数据：查看血缘');
INSERT INTO sys_permission (id, code, name, description) VALUES (130, 'collect:view', '查看', '元数据采集任务：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (131, 'collect:create', '新建', '元数据采集任务：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (132, 'collect:update', '编辑', '元数据采集任务：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (133, 'collect:delete', '删除', '元数据采集任务：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (134, 'collect:execute', '手动触发采集', '元数据采集任务：手动触发采集');
INSERT INTO sys_permission (id, code, name, description) VALUES (135, 'collect:history', '查看采集历史', '元数据采集任务：查看采集历史');
INSERT INTO sys_permission (id, code, name, description) VALUES (136, 'standard:view', '查看', '数据标准：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (137, 'standard:create', '新建', '数据标准：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (138, 'standard:update', '编辑', '数据标准：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (139, 'standard:delete', '删除', '数据标准：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (140, 'compliance:view', '查看', '标准合规：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (141, 'compliance:handle', '处理', '标准合规：处理（忽略·标记）');
INSERT INTO sys_permission (id, code, name, description) VALUES (142, 'quality_rule:view', '查看', '质量规则：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (143, 'quality_rule:create', '新建', '质量规则：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (144, 'quality_rule:update', '编辑', '质量规则：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (145, 'quality_rule:delete', '删除', '质量规则：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (146, 'quality_job:view', '查看', '质量任务：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (147, 'quality_job:create', '新建', '质量任务：新建');
INSERT INTO sys_permission (id, code, name, description) VALUES (148, 'quality_job:update', '编辑', '质量任务：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (149, 'quality_job:delete', '删除', '质量任务：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (150, 'quality_job:execute', '手动触发检查', '质量任务：手动触发检查');
INSERT INTO sys_permission (id, code, name, description) VALUES (151, 'quality_job:history', '查看检查历史', '质量任务：查看检查历史');
INSERT INTO sys_permission (id, code, name, description) VALUES (152, 'quality_result:score', '查看评分', '质量结果：查看评分');
INSERT INTO sys_permission (id, code, name, description) VALUES (153, 'quality_result:report', '查看报告', '质量结果：查看报告');
INSERT INTO sys_permission (id, code, name, description) VALUES (154, 'asset:view', '查看', '资产目录：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (155, 'asset:collab', '收藏·关注', '资产目录：收藏·关注');
INSERT INTO sys_permission (id, code, name, description) VALUES (156, 'asset:comment', '评论', '资产目录：评论');
INSERT INTO sys_permission (id, code, name, description) VALUES (157, 'sql:execute', '执行查询', 'SQL 查询终端：执行查询');
INSERT INTO sys_permission (id, code, name, description) VALUES (158, 'sql:export', '导出结果', 'SQL 查询终端：导出结果');
INSERT INTO sys_permission (id, code, name, description) VALUES (159, 'sql:history', '查看查询历史', 'SQL 查询终端：查看查询历史');
INSERT INTO sys_permission (id, code, name, description) VALUES (160, 'api:view', '查看', '数据 API：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (161, 'api:create', '创建', '数据 API：创建');
INSERT INTO sys_permission (id, code, name, description) VALUES (162, 'api:update', '编辑', '数据 API：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (163, 'api:publish', '发布·下线', '数据 API：发布·下线');
INSERT INTO sys_permission (id, code, name, description) VALUES (164, 'api:delete', '删除', '数据 API：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (165, 'api:stats', '查看统计', '数据 API：查看统计');
INSERT INTO sys_permission (id, code, name, description) VALUES (166, 'api_key:view', '查看', 'API Key 管理：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (167, 'api_key:create', '创建', 'API Key 管理：创建');
INSERT INTO sys_permission (id, code, name, description) VALUES (168, 'api_key:toggle', '禁用·启用', 'API Key 管理：禁用·启用');
INSERT INTO sys_permission (id, code, name, description) VALUES (169, 'api_key:delete', '删除', 'API Key 管理：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (170, 'sensitivity:view', '查看', '数据分级分类：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (171, 'sensitivity:change', '单表改级', '数据分级分类：单表改级');
INSERT INTO sys_permission (id, code, name, description) VALUES (172, 'sensitivity:batch_change', '批量改级', '数据分级分类：批量改级');
INSERT INTO sys_permission (id, code, name, description) VALUES (173, 'alert:view', '查看告警', '告警中心：查看告警');
INSERT INTO sys_permission (id, code, name, description) VALUES (174, 'alert:rule_manage', '新建·编辑规则', '告警中心：新建·编辑规则');
INSERT INTO sys_permission (id, code, name, description) VALUES (175, 'user:view', '查看', '用户管理：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (176, 'user:create', '创建', '用户管理：创建');
INSERT INTO sys_permission (id, code, name, description) VALUES (177, 'user:update', '编辑', '用户管理：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (178, 'user:toggle', '禁用·启用', '用户管理：禁用·启用');
INSERT INTO sys_permission (id, code, name, description) VALUES (179, 'user:reset_pwd', '重置密码', '用户管理：重置密码');
INSERT INTO sys_permission (id, code, name, description) VALUES (180, 'role:view', '查看', '角色管理：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (181, 'role:create', '创建', '角色管理：创建');
INSERT INTO sys_permission (id, code, name, description) VALUES (182, 'role:update', '编辑', '角色管理：编辑');
INSERT INTO sys_permission (id, code, name, description) VALUES (183, 'role:delete', '删除', '角色管理：删除');
INSERT INTO sys_permission (id, code, name, description) VALUES (184, 'data_permission:manage', '配置', '权限配置：配置');
INSERT INTO sys_permission (id, code, name, description) VALUES (185, 'audit:view', '查看', '审计日志：查看');
INSERT INTO sys_permission (id, code, name, description) VALUES (186, 'queue:manage', '管理', '执行队列：管理');
INSERT INTO sys_permission (id, code, name, description) VALUES (187, 'asset:manage', '分类维护·负责人分配', '资产目录：分类体系维护与负责人分配');

-- 超级管理员：全部权限点
INSERT INTO sys_role_permission (id, role_id, permission_id, created_at)
SELECT 1000000 + row_number() OVER (), r.id, p.id, CURRENT_TIMESTAMP FROM sys_role r CROSS JOIN sys_permission p WHERE r.code = 'SUPER_ADMIN';

-- 数据工程师（PRD §6.2.1 矩阵）
INSERT INTO sys_role_permission (id, role_id, permission_id, created_at)
SELECT 2000000 + row_number() OVER (), r.id, p.id, CURRENT_TIMESTAMP FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'DATA_ENGINEER' AND p.code IN (
  'datasource:view','datasource:create','datasource:update','datasource:delete','datasource:test',
  'sync:view','sync:create','sync:update','sync:delete','sync:execute','sync:history',
  'cdc:view','cdc:create','cdc:update','cdc:delete','cdc:execute','cdc:monitor',
  'dag:view','dag:create','dag:update','dag:delete','dag:execute','dag:history',
  'template:view','template:create','template:update','template:delete',
  'metadata:view','metadata:comment','metadata:lineage',
  'compliance:view','quality_result:score','quality_result:report',
  'asset:view','asset:collab','asset:comment',
  'sql:execute','sql:export','sql:history',
  'api:view','api:create','api:update','api:publish','api:delete','api:stats',
  'api_key:view','api_key:create','api_key:toggle','api_key:delete',
  'sensitivity:view','alert:view','alert:rule_manage');

-- 数据分析师（PRD §6.2.1 矩阵）
INSERT INTO sys_role_permission (id, role_id, permission_id, created_at)
SELECT 3000000 + row_number() OVER (), r.id, p.id, CURRENT_TIMESTAMP FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'DATA_ANALYST' AND p.code IN (
  'cdc:view','cdc:monitor','dag:view','dag:history',
  'metadata:view','quality_result:score','quality_result:report',
  'asset:view','asset:collab','asset:comment',
  'sql:execute','sql:export','sql:history',
  'api:view','api:stats','api_key:view','sensitivity:view');

-- 治理管理员（PRD §6.2.1 矩阵）
INSERT INTO sys_role_permission (id, role_id, permission_id, created_at)
SELECT 4000000 + row_number() OVER (), r.id, p.id, CURRENT_TIMESTAMP FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'GOVERNANCE_ADMIN' AND p.code IN (
  'datasource:view','cdc:view','cdc:monitor','dag:view','dag:history',
  'metadata:view','metadata:comment','metadata:lineage',
  'collect:view','collect:create','collect:update','collect:delete','collect:execute','collect:history',
  'standard:view','standard:create','standard:update','standard:delete',
  'compliance:view','compliance:handle',
  'quality_rule:view','quality_rule:create','quality_rule:update','quality_rule:delete',
  'quality_job:view','quality_job:create','quality_job:update','quality_job:delete','quality_job:execute','quality_job:history',
  'quality_result:score','quality_result:report',
  'asset:view','asset:collab','asset:comment','asset:manage',
  'api:view','api:stats','api_key:view',
  'sensitivity:view','sensitivity:change','sensitivity:batch_change',
  'alert:view');

-- 三级数据权限白名单表（默认全量可见 = 无记录）
CREATE TABLE IF NOT EXISTS sys_data_permission (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    datasource_id bigint NOT NULL,
    database_name character varying(128),
    table_name character varying(128),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT sys_data_permission_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_data_permission ON sys_data_permission (role_id, datasource_id, database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_sys_data_permission_role ON sys_data_permission (role_id);

COMMENT ON TABLE sys_data_permission IS '角色数据权限白名单（Sprint 11 F2，默认全量可见=无记录；最细粒度优先）';
COMMENT ON COLUMN sys_data_permission.role_id IS '角色 ID';
COMMENT ON COLUMN sys_data_permission.datasource_id IS '数据源 ID';
COMMENT ON COLUMN sys_data_permission.database_name IS '数据库名（可空=库级通配）';
COMMENT ON COLUMN sys_data_permission.table_name IS '表名（可空=表级通配）';
