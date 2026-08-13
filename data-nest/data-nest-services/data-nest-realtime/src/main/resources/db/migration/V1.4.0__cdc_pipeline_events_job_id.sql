ALTER TABLE public.cdc_pipeline ADD COLUMN IF NOT EXISTS cdc_events_flink_job_id character varying(64);
COMMENT ON COLUMN public.cdc_pipeline.cdc_events_flink_job_id IS 'F4 WebSocket 实时订阅事件作业 Flink Job ID（Kafka 单 sink 事件管道，与主管道同生命周期；RUNNING 时有值）';
