-- Sprint 13：data_api 双形态扩展——query_type 查询定义形态（TABLE_SELECT 选表 / CUSTOM_SQL 自定义 SQL）+ 自定义 SQL 文本与涉及表清单
ALTER TABLE public.data_api ADD COLUMN IF NOT EXISTS query_type character varying(20) DEFAULT 'TABLE_SELECT'::character varying NOT NULL;
COMMENT ON COLUMN public.data_api.query_type IS '查询定义形态（Sprint 13）：TABLE_SELECT 选表 / CUSTOM_SQL 自定义 SQL';
ALTER TABLE public.data_api ADD COLUMN IF NOT EXISTS sql_text text;
COMMENT ON COLUMN public.data_api.sql_text IS '自定义 SQL 文本（CUSTOM_SQL 形态，只读 SELECT，:param 命名参数）';
ALTER TABLE public.data_api ADD COLUMN IF NOT EXISTS involved_tables text;
COMMENT ON COLUMN public.data_api.involved_tables IS 'SQL 涉及表清单 JSON（[{datasourceId,database,schema,table}]，创建/编辑时解析落库）';
