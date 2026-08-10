ALTER TABLE public.cdc_pipeline ADD COLUMN IF NOT EXISTS started_at timestamp without time zone;
COMMENT ON COLUMN public.cdc_pipeline.started_at IS '最近一次启动成功时间（2026-08-10 运行时长支撑；stop 不清空，启动成功后写入）';
