-- ============================================
-- V1.7.0__sprint11_queue.sql
-- Sprint 11 F3 任务资源队列：
--   1) execution_queue 队列表（名称/最大并发/描述/系统队列标记）
--   2) dag 扩展 queue_name（默认 default）/ priority（1=低 2=中 3=高）
--   3) dag_execution 扩展 queue_name / priority（执行历史展示队列与优先级）
--   4) 内置 default 队列种子（max_concurrency=10，is_system=true 不可删）
-- 注意：紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配；
--       版本号 V1.7.0 > 本库最高 V1.6.0（V1.5.0 任务模板 / V1.6.0 模板值类型）。
-- ============================================

CREATE TABLE IF NOT EXISTS public.execution_queue (id bigint NOT NULL, queue_name character varying(64) NOT NULL, max_concurrency integer DEFAULT 10 NOT NULL, description character varying(256), is_system boolean DEFAULT false NOT NULL, created_by bigint, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL, updated_by bigint, updated_at timestamp without time zone, CONSTRAINT execution_queue_pkey PRIMARY KEY (id));
CREATE UNIQUE INDEX IF NOT EXISTS uk_execution_queue_name ON public.execution_queue USING btree (queue_name);
ALTER TABLE public.dag ADD COLUMN IF NOT EXISTS queue_name character varying(64) DEFAULT 'default' NOT NULL;
ALTER TABLE public.dag ADD COLUMN IF NOT EXISTS priority smallint DEFAULT 2 NOT NULL;
ALTER TABLE public.dag_execution ADD COLUMN IF NOT EXISTS queue_name character varying(64) DEFAULT 'default';
ALTER TABLE public.dag_execution ADD COLUMN IF NOT EXISTS priority smallint DEFAULT 2;
CREATE INDEX IF NOT EXISTS idx_dag_execution_queue_status ON public.dag_execution USING btree (queue_name, status);
CREATE INDEX IF NOT EXISTS idx_dag_execution_priority ON public.dag_execution USING btree (priority DESC, created_at ASC) WHERE status = 'WAITING';
INSERT INTO public.execution_queue (id, queue_name, max_concurrency, description, is_system, created_at) VALUES (1, 'default', 10, '默认队列，未指定队列的 DAG 均在此排队', true, CURRENT_TIMESTAMP) ON CONFLICT (queue_name) DO NOTHING;
