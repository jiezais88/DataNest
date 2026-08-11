CREATE TABLE IF NOT EXISTS public.cdc_metric_minute (id bigint NOT NULL, pipeline_id bigint NOT NULL, minute_at timestamp without time zone NOT NULL, lag_avg_seconds integer, lag_max_seconds integer, records_per_second_avg double precision, num_restarts integer, total_changes bigint, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL, CONSTRAINT cdc_metric_minute_pkey PRIMARY KEY (id), CONSTRAINT uk_cdc_metric_minute_pipeline_minute UNIQUE (pipeline_id, minute_at));
COMMENT ON TABLE public.cdc_metric_minute IS 'CDC 管道分钟级指标历史（5s 轮询内存聚合后每分钟 upsert 一行，保留 30 天）';
COMMENT ON COLUMN public.cdc_metric_minute.minute_at IS '采样分钟（截断到分）';
COMMENT ON COLUMN public.cdc_metric_minute.lag_avg_seconds IS '本分钟延迟均值（秒）；该分钟无有效样本为 NULL';
COMMENT ON COLUMN public.cdc_metric_minute.lag_max_seconds IS '本分钟延迟峰值（秒），趋势图标红判定用';
COMMENT ON COLUMN public.cdc_metric_minute.records_per_second_avg IS '本分钟吞吐均值（行/秒，sink vertex numRecordsOutPerSecond 求和后按分钟平均）';
COMMENT ON COLUMN public.cdc_metric_minute.num_restarts IS '作业累计重启次数（该分钟最后一次采样值，job-level numRestarts）';
COMMENT ON COLUMN public.cdc_metric_minute.total_changes IS '累计变更数（该分钟最后一次采样值）';
CREATE INDEX IF NOT EXISTS idx_cdc_metric_minute_minute_at ON public.cdc_metric_minute USING btree (minute_at);
