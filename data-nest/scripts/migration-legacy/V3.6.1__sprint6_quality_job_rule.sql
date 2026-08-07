-- ============================================
-- V3.6.1__sprint6_quality_job_rule.sql
-- Sprint 6：质量任务 + 质量规则（配置层，D-D1/D-D3）
-- 说明：本次仅建配置层两张表 quality_job / quality_rule。
--       批次/历史/评分表（quality_check_batch/history/score）留到执行校验批一并建。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避 V3.6.0 被格式化工具拆行导致的可读性/维护问题。
-- ============================================

-- --------------------------------------------
-- quality_job 质量任务
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS quality_job (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    datasource_id BIGINT, -- 可选，数据源范围（用于选表过滤）
    enabled SMALLINT NOT NULL DEFAULT 1,
    scheduled_enabled SMALLINT NOT NULL DEFAULT 0, -- 是否开定时调度（D1）
    cron VARCHAR(64), -- 定时 cron（scheduled_enabled=1 时必填）
    auto_trigger_enabled SMALLINT NOT NULL DEFAULT 0, -- 是否任务完成自动触发（D1）
    auto_trigger_object_type VARCHAR(30), -- DAG_NODE / SYNC_JOB / COLLECT_TASK
    auto_trigger_object_id BIGINT,
    alert_level VARCHAR(20) NOT NULL DEFAULT 'SEVERE_WARNING', -- 告警触发等级：SEVERE_ONLY / SEVERE_WARNING
    last_trigger_at TIMESTAMP, -- 最近一次触发时间（防重 R6）
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quality_job_name UNIQUE (name)
);

-- --------------------------------------------
-- quality_rule 质量规则实例（挂任务下）
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS quality_rule (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL, -- 所属质量任务
    template_id BIGINT, -- 来源模板（可空，自定义 SQL 也记）
    name VARCHAR(100) NOT NULL, -- 规则名称
    type VARCHAR(20) NOT NULL, -- COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL
    table_id BIGINT, -- 目标表 metadata_table.id
    column_name VARCHAR(128), -- 检查字段（唯一性/值域必填；完整性可空）
    check_field SMALLINT NOT NULL DEFAULT 0, -- 是否按字段检查（完整性填字段时=1，整表=0）
    sql_expression TEXT, -- 实际校验 SQL（执行时动态生成，本次不落库；自定义 SQL 除外）
    warning_threshold DECIMAL(20,6), -- 警告阈值（执行结果 ≥ 此值 → 警告）
    severe_threshold DECIMAL(20,6), -- 严重阈值（执行结果 ≥ 此值 → 严重）
    result_metric VARCHAR(50), -- 结果指标名
    weight INT NOT NULL DEFAULT 1, -- 权重（评分加权，默认 1）
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quality_rule_job_table_name UNIQUE (job_id, table_id, name)
);

CREATE INDEX IF NOT EXISTS idx_quality_rule_table_id ON quality_rule (table_id);

COMMENT ON TABLE quality_job IS '质量任务（Sprint 6 配置层）';
COMMENT ON COLUMN quality_job.name IS '任务名称（唯一）';
COMMENT ON COLUMN quality_job.datasource_id IS '可选，数据源范围（用于选表过滤）';
COMMENT ON COLUMN quality_job.scheduled_enabled IS '是否开定时调度（D1）';
COMMENT ON COLUMN quality_job.cron IS '定时 cron（scheduled_enabled=1 时必填）';
COMMENT ON COLUMN quality_job.auto_trigger_enabled IS '是否任务完成自动触发（D1）';
COMMENT ON COLUMN quality_job.auto_trigger_object_type IS '自动触发绑定对象类型：DAG_NODE / SYNC_JOB / COLLECT_TASK';
COMMENT ON COLUMN quality_job.alert_level IS '告警触发等级：SEVERE_ONLY / SEVERE_WARNING';
COMMENT ON COLUMN quality_job.last_trigger_at IS '最近一次触发时间（防重 R6）';

COMMENT ON TABLE quality_rule IS '质量规则实例（Sprint 6 配置层，挂任务下）';
COMMENT ON COLUMN quality_rule.job_id IS '所属质量任务';
COMMENT ON COLUMN quality_rule.template_id IS '来源模板（可空，自定义 SQL 也记）';
COMMENT ON COLUMN quality_rule.name IS '规则名称';
COMMENT ON COLUMN quality_rule.type IS '规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL';
COMMENT ON COLUMN quality_rule.table_id IS '目标表 metadata_table.id';
COMMENT ON COLUMN quality_rule.column_name IS '检查字段（唯一性/值域必填；完整性可空）';
COMMENT ON COLUMN quality_rule.check_field IS '是否按字段检查（完整性填字段时=1，整表=0）';
COMMENT ON COLUMN quality_rule.sql_expression IS '实际校验 SQL（执行时动态生成，本次不落库；自定义 SQL 除外）';
COMMENT ON COLUMN quality_rule.warning_threshold IS '警告阈值（执行结果 ≥ 此值 → 警告）';
COMMENT ON COLUMN quality_rule.severe_threshold IS '严重阈值（执行结果 ≥ 此值 → 严重）';
COMMENT ON COLUMN quality_rule.result_metric IS '结果指标名';
COMMENT ON COLUMN quality_rule.weight IS '权重（评分加权，默认 1）';
