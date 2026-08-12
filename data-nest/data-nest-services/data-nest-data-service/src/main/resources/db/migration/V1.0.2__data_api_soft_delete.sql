-- Sprint 10 F2：data_api 软删 + params_json 语义升级为完整 API 定义对象
-- 软删对齐 PRD「删除保留调用统计」；path 唯一约束改为部分唯一索引（仅未删除行占用，删除后路径可复用）
ALTER TABLE public.data_api ADD COLUMN IF NOT EXISTS deleted smallint DEFAULT 0 NOT NULL;
COMMENT ON COLUMN public.data_api.deleted IS '软删标记（Sprint 10 F2）：0 正常 / 1 已删除，软删保留 api_call_log 调用统计';
ALTER TABLE public.data_api DROP CONSTRAINT IF EXISTS uk_data_api_path;
CREATE UNIQUE INDEX IF NOT EXISTS uk_data_api_path ON public.data_api USING btree (path) WHERE deleted = 0;
COMMENT ON COLUMN public.data_api.params_json IS 'API 定义 JSON（Sprint 10 F2）：{"filters":[{"field","type":"EQ|RANGE"}],"fields":["返回字段白名单，空=全部"]}';
