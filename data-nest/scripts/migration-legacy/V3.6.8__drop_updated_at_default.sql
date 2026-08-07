-- ============================================
-- V3.6.8__drop_updated_at_default.sql
-- 去除各业务表 updated_at 的 DB 默认值与 NOT NULL 约束
-- 目标：创建时不再自动填 updated_at（保持 null），仅真正修改时才写入
-- 说明：历史数据不改动；仅改列定义。脚本用紧凑单行，勿用格式化工具拆分
-- ============================================

ALTER TABLE sync_job ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE datasource_connection ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE collect_task ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE dag_project ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE dag ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE dag_node ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE dag_parameter ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE quality_rule_template ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE quality_job ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE quality_rule ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE naming_standard ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE field_type_standard ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE sys_user ALTER COLUMN updated_at DROP DEFAULT, ALTER COLUMN updated_at DROP NOT NULL;
