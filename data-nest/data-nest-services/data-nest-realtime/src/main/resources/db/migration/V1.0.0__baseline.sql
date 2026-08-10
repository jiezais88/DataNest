-- realtime 域基线（Sprint 8 F2 实时 CDC 管道，全新第 5 业务库 datanest_realtime）
-- 主键走雪花（应用层 ASSIGN_ID），不建序列；updated_at 无 DB 默认值（仅真实更新时写入）；紧凑单行风格
CREATE TABLE IF NOT EXISTS public.cdc_pipeline (id bigint NOT NULL, name character varying(100) NOT NULL, source_datasource_id bigint NOT NULL, source_database character varying(100) NOT NULL, target_database character varying(100) NOT NULL, sync_mode character varying(20) NOT NULL, startup_mode character varying(20) NOT NULL, write_mode character varying(20) NOT NULL, status character varying(20) DEFAULT 'STOPPED'::character varying NOT NULL, flink_job_id character varying(64), savepoint_path character varying(500), current_lag_seconds integer, total_changes bigint DEFAULT 0, last_error character varying(2000), config_json text, created_by bigint, updated_by bigint, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL, updated_at timestamp without time zone, CONSTRAINT cdc_pipeline_pkey PRIMARY KEY (id), CONSTRAINT uk_cdc_pipeline_name UNIQUE (name));
COMMENT ON TABLE public.cdc_pipeline IS 'CDC 实时同步管道（MySQL binlog → Flink CDC → Iceberg 湖仓）';
COMMENT ON COLUMN public.cdc_pipeline.source_datasource_id IS '源数据源 ID（engineering datasource_connection.id，跨域 Feign 反查）';
COMMENT ON COLUMN public.cdc_pipeline.source_database IS '源库名（MySQL database）';
COMMENT ON COLUMN public.cdc_pipeline.target_database IS '目标库名（Iceberg/Doris catalog 下的 database）';
COMMENT ON COLUMN public.cdc_pipeline.sync_mode IS '同步模式：FULL_AND_INCREMENT 全量+增量 / INCREMENTAL_ONLY 仅增量';
COMMENT ON COLUMN public.cdc_pipeline.startup_mode IS '启动位点：INITIAL 全量快照+增量 / LATEST_OFFSET 从最新位点 / EARLIEST_OFFSET 从最早位点（2026-08-10 按 Flink 语义修正）';
COMMENT ON COLUMN public.cdc_pipeline.write_mode IS '写入模式：UPSERT 主键覆盖 / APPEND 追加';
COMMENT ON COLUMN public.cdc_pipeline.status IS '管道状态：STOPPED/RUNNING/ERROR';
COMMENT ON COLUMN public.cdc_pipeline.flink_job_id IS 'Flink 作业 ID（RUNNING 时有值）';
COMMENT ON COLUMN public.cdc_pipeline.savepoint_path IS '最近一次 stop-with-savepoint 的 savepoint 路径（s3a://...，启动优先恢复；编辑后清空）';
COMMENT ON COLUMN public.cdc_pipeline.current_lag_seconds IS '当前同步延迟（秒），监控轮询回写；-1/NULL 表示未知';
COMMENT ON COLUMN public.cdc_pipeline.total_changes IS '累计写入变更条数（sink numRecordsOut），监控轮询回写';
COMMENT ON COLUMN public.cdc_pipeline.last_error IS '最近一次错误信息（截断 2000）';
COMMENT ON COLUMN public.cdc_pipeline.config_json IS '扩展配置 JSON（预留）';
CREATE INDEX IF NOT EXISTS idx_cdc_pipeline_status ON public.cdc_pipeline USING btree (status);
CREATE INDEX IF NOT EXISTS idx_cdc_pipeline_source_datasource ON public.cdc_pipeline USING btree (source_datasource_id);
CREATE TABLE IF NOT EXISTS public.cdc_pipeline_table (id bigint NOT NULL, pipeline_id bigint NOT NULL, source_table character varying(200) NOT NULL, target_table character varying(200) NOT NULL, primary_key character varying(500), created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL, CONSTRAINT cdc_pipeline_table_pkey PRIMARY KEY (id));
COMMENT ON TABLE public.cdc_pipeline_table IS 'CDC 管道表级映射（源表 → 目标表）';
COMMENT ON COLUMN public.cdc_pipeline_table.primary_key IS '目标表主键列（逗号分隔，UPSERT 模式必填）';
CREATE INDEX IF NOT EXISTS idx_cdc_pipeline_table_pipeline ON public.cdc_pipeline_table USING btree (pipeline_id);
CREATE TABLE IF NOT EXISTS public.cdc_pipeline_log (id bigint NOT NULL, pipeline_id bigint NOT NULL, level character varying(10) NOT NULL, message text NOT NULL, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL, CONSTRAINT cdc_pipeline_log_pkey PRIMARY KEY (id));
COMMENT ON TABLE public.cdc_pipeline_log IS 'CDC 管道运行日志（创建/启停/状态变更/延迟告警）';
COMMENT ON COLUMN public.cdc_pipeline_log.level IS '日志级别：INFO/WARN/ERROR';
CREATE INDEX IF NOT EXISTS idx_cdc_pipeline_log_pipeline_id ON public.cdc_pipeline_log USING btree (pipeline_id, id DESC);
