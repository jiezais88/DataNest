-- ============================================
-- Sprint 10 F5 数据分级分类：metadata_table 加敏感度/API 开白 + 分级变更审计表
-- 注意：版本号 1.6.0 > 库内最高 1.5.0，确保 Flyway 顺序执行
-- ============================================
ALTER TABLE public.metadata_table ADD COLUMN IF NOT EXISTS sensitivity_level character varying(20) DEFAULT 'PUBLIC'::character varying NOT NULL;
COMMENT ON COLUMN public.metadata_table.sensitivity_level IS '数据敏感度（Sprint 10 F5）：PUBLIC 公开 / INTERNAL 内部 / CONFIDENTIAL 机密，默认公开';
ALTER TABLE public.metadata_table ADD COLUMN IF NOT EXISTS api_exempted smallint DEFAULT 0 NOT NULL;
COMMENT ON COLUMN public.metadata_table.api_exempted IS '内部表生成对外 API 的超管强制开白标记（Sprint 10 F5，T6）；机密表恒为 0 不可开白';

CREATE TABLE IF NOT EXISTS public.sensitivity_change_log (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    table_name character varying(200) NOT NULL,
    old_level character varying(20),
    new_level character varying(20) NOT NULL,
    operator_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sensitivity_change_log_pkey PRIMARY KEY (id)
);
COMMENT ON TABLE public.sensitivity_change_log IS '数据分级变更审计（Sprint 10 F5）：谁/何时/从哪级到哪级';
