-- ============================================
-- V3.6.3__sprint7_quality_rule_independent.sql
-- Sprint 7：质量规则独立化（D 规则独立菜单 + 任务多对多引用）
-- 说明：
--   1) quality_rule.job_id 改为可空（规则可独立创建，不强制绑定任务）
--   2) 新增 quality_job_rule 关联表（质量任务 <-> 质量规则 多对多）
--   3) 历史数据回填：把现有 quality_rule.job_id 迁入 quality_job_rule
--   4) 唯一约束调整：规则名称全局唯一（删除旧的 job_id+table_id+name 约束）
-- 注意：本脚本采用紧凑单行 SQL 写法，规避被格式化工具拆行导致的问题。
-- ============================================

-- 1) job_id 改为可空
ALTER TABLE quality_rule ALTER COLUMN job_id DROP NOT NULL;

-- 2) 新增质量任务-规则 多对多关联表
CREATE TABLE IF NOT EXISTS quality_job_rule (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quality_job_rule UNIQUE (job_id, rule_id)
);

CREATE INDEX IF NOT EXISTS idx_quality_job_rule_rule_id ON quality_job_rule (rule_id);

COMMENT ON TABLE quality_job_rule IS '质量任务-质量规则 多对多关联表（Sprint 7 规则独立化）';
COMMENT ON COLUMN quality_job_rule.job_id IS '质量任务 ID';
COMMENT ON COLUMN quality_job_rule.rule_id IS '质量规则 ID';

-- 3) 历史数据回填：把现有 quality_rule.job_id 迁入关联表
INSERT INTO quality_job_rule (job_id, rule_id)
SELECT job_id, id FROM quality_rule
WHERE job_id IS NOT NULL
ON CONFLICT (job_id, rule_id) DO NOTHING;

-- 4) 唯一约束调整：删除旧的 job_id+table_id+name 约束，改为规则名称全局唯一
ALTER TABLE quality_rule DROP CONSTRAINT IF EXISTS uk_quality_rule_job_table_name;

-- 清理历史同名规则（保留每个 name 中 id 最小的一条），避免全局唯一约束冲突
DELETE FROM quality_rule r
USING quality_rule k
WHERE r.name = k.name AND r.id > k.id;

ALTER TABLE quality_rule ADD CONSTRAINT uk_quality_rule_name UNIQUE (name);

COMMENT ON COLUMN quality_rule.job_id IS '所属质量任务（可空；规则独立创建后通过 quality_job_rule 关联任务）';
