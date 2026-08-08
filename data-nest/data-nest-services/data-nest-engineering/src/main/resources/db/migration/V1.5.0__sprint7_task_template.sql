-- ============================================
-- V1.5.0__sprint7_task_template.sql
-- Sprint 7 F2 任务模板库（DD-09）：task_template 表 + 3 条内置模板播种
-- 范围经用户确认（2026-08-08）：仅 SYNC / COLLECT 两类（SQL 无独立任务实体、EXPORT 平台不存在，本期不做）
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配；
--       updated_at 不设 DB 默认值（审计字段约定：仅真正更新时由代码写入）；
--       内置模板 created_by 为 NULL（前端展示「系统」），id 用固定值避免与雪花 ID 冲突。
-- ============================================

CREATE TABLE IF NOT EXISTS task_template (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(20) NOT NULL,
    category character varying(20) NOT NULL,
    description character varying(500) DEFAULT NULL::character varying,
    config_template text NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT task_template_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_template_name ON task_template (name);
CREATE INDEX IF NOT EXISTS idx_task_template_type ON task_template (type);

COMMENT ON TABLE task_template IS '任务模板库（Sprint 7 DD-09，区别于质量规则模板 quality_rule_template）';
COMMENT ON COLUMN task_template.id IS '主键ID（雪花；内置模板为 91 开头固定值）';
COMMENT ON COLUMN task_template.name IS '模板名称（全局唯一）';
COMMENT ON COLUMN task_template.type IS '任务类型：SYNC 数据同步 / COLLECT 元数据采集';
COMMENT ON COLUMN task_template.category IS '来源：BUILTIN 内置（禁改禁删）/ CUSTOM 自定义';
COMMENT ON COLUMN task_template.description IS '模板说明';
COMMENT ON COLUMN task_template.config_template IS 'JSON 模板：{"placeholders":[{key,label,required,valueType,defaultValue}],"config":{对应类型创建请求，字符串值含 {key} 占位符}}';
COMMENT ON COLUMN task_template.enabled IS '是否启用：0-停用 1-启用';
COMMENT ON COLUMN task_template.created_by IS '创建人ID（内置模板为 NULL）';
COMMENT ON COLUMN task_template.updated_by IS '更新人ID';
COMMENT ON COLUMN task_template.created_at IS '创建时间';
COMMENT ON COLUMN task_template.updated_at IS '更新时间';

INSERT INTO task_template (id, name, type, category, description, config_template, enabled, created_by, created_at) VALUES (910000000000000001, '整表同步', 'SYNC', 'BUILTIN', '源→目标整表同步，含数据源/库表占位符', '{"placeholders":[{"key":"source_datasource","label":"源数据源","required":true,"valueType":"DATASOURCE"},{"key":"source_db","label":"源库名","required":true},{"key":"source_table","label":"源表名","required":true},{"key":"target_db","label":"目标库","required":true},{"key":"target_table","label":"目标表","required":true}],"config":{"sourceDatasourceId":"{source_datasource}","sourceDatabase":"{source_db}","sourceTables":["{source_table}"],"syncMode":"FULL","triggerType":"MANUAL","targetDatabase":"{target_db}","targetTable":"{target_table}"}}', 1, NULL, CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING;
INSERT INTO task_template (id, name, type, category, description, config_template, enabled, created_by, created_at) VALUES (910000000000000002, '增量同步（每日）', 'SYNC', 'BUILTIN', '按增量字段每日定时同步，含调度 Cron 占位符', '{"placeholders":[{"key":"source_datasource","label":"源数据源","required":true,"valueType":"DATASOURCE"},{"key":"source_db","label":"源库名","required":true},{"key":"source_table","label":"源表名","required":true},{"key":"incremental_field","label":"增量字段","required":true},{"key":"target_db","label":"目标库","required":true},{"key":"target_table","label":"目标表","required":true},{"key":"schedule_cron","label":"调度 Cron","required":false,"defaultValue":"0 0 2 * * ?"}],"config":{"sourceDatasourceId":"{source_datasource}","sourceDatabase":"{source_db}","sourceTables":["{source_table}"],"syncMode":"INCREMENTAL","incrementalField":"{incremental_field}","triggerType":"CRON","cronExpression":"{schedule_cron}","targetDatabase":"{target_db}","targetTable":"{target_table}"}}', 1, NULL, CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING;
INSERT INTO task_template (id, name, type, category, description, config_template, enabled, created_by, created_at) VALUES (910000000000000003, '元数据全量采集', 'COLLECT', 'BUILTIN', '从源库全量采集元数据（库/表/字段）到数据治理', '{"placeholders":[{"key":"datasource","label":"数据源","required":true,"valueType":"DATASOURCE"},{"key":"scope","label":"采集库名（schema）","required":true}],"config":{"datasourceId":"{datasource}","scope":["{scope}"],"collectMode":"FULL","triggerType":"MANUAL"}}', 1, NULL, CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING;
