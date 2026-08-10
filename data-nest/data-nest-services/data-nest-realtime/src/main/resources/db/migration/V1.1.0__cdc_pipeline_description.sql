ALTER TABLE public.cdc_pipeline ADD COLUMN IF NOT EXISTS description character varying(500);
COMMENT ON COLUMN public.cdc_pipeline.description IS '管道描述（2026-08-10 前端联调确认补齐，向导第①步）';
