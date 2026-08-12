-- ============================================
-- datanest_dataservice 域基线（Sprint 10 数据服务，全新第 6 业务库）
-- 6 表：data_api / api_key / api_key_binding / api_key_pipeline / api_call_log / sql_query_history
-- 审计字段约定：新表 create 只设 created_by/created_at，updated_at 无 DB 默认值，仅真正 update 写入
-- ============================================

CREATE TABLE IF NOT EXISTS public.data_api (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    path character varying(200) NOT NULL,
    method character varying(10) DEFAULT 'GET'::character varying NOT NULL,
    datasource_id bigint NOT NULL,
    database_name character varying(100) NOT NULL,
    schema_name character varying(100),
    table_name character varying(100) NOT NULL,
    metadata_table_id bigint,
    params_json text,
    order_by character varying(100),
    paginated smallint DEFAULT 1 NOT NULL,
    page_size_max integer DEFAULT 100 NOT NULL,
    status character varying(20) DEFAULT 'CREATED'::character varying NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT data_api_pkey PRIMARY KEY (id),
    CONSTRAINT uk_data_api_path UNIQUE (path)
);
COMMENT ON TABLE public.data_api IS '数据 API 定义（Sprint 10 F2）：表级参数化查询 API';
COMMENT ON COLUMN public.data_api.params_json IS '参数化筛选/范围字段 JSON（[{field,type,operator}]）';
COMMENT ON COLUMN public.data_api.status IS 'CREATED 未发布 / PUBLISHED 可调用 / DISABLED 下线';

CREATE TABLE IF NOT EXISTS public.api_key (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    key_hash character varying(64) NOT NULL,
    qps_limit integer DEFAULT 10 NOT NULL,
    status character varying(20) DEFAULT 'ENABLED'::character varying NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT api_key_pkey PRIMARY KEY (id),
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash)
);
COMMENT ON TABLE public.api_key IS 'API Key（SHA-256 哈希存储，明文仅创建时展示一次）';

CREATE TABLE IF NOT EXISTS public.api_key_binding (
    id bigint NOT NULL,
    key_id bigint NOT NULL,
    api_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT api_key_binding_pkey PRIMARY KEY (id),
    CONSTRAINT uk_api_key_binding UNIQUE (key_id, api_id)
);
COMMENT ON TABLE public.api_key_binding IS 'Key-API 绑定';

CREATE TABLE IF NOT EXISTS public.api_key_pipeline (
    id bigint NOT NULL,
    key_id bigint NOT NULL,
    pipeline_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT api_key_pipeline_pkey PRIMARY KEY (id),
    CONSTRAINT uk_api_key_pipeline UNIQUE (key_id, pipeline_id)
);
COMMENT ON TABLE public.api_key_pipeline IS 'Key-管道订阅授权（WebSocket 实时订阅，T7）';

CREATE TABLE IF NOT EXISTS public.api_call_log (
    id bigint NOT NULL,
    api_id bigint,
    key_id bigint,
    status_code integer NOT NULL,
    duration_ms integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT api_call_log_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_api_call_log_created_at ON public.api_call_log USING btree (created_at);
CREATE INDEX IF NOT EXISTS idx_api_call_log_api_time ON public.api_call_log USING btree (api_id, created_at);

CREATE TABLE IF NOT EXISTS public.sql_query_history (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    datasource_id bigint,
    sql_text text NOT NULL,
    duration_ms integer,
    row_count integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sql_query_history_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sql_query_history_user_time ON public.sql_query_history USING btree (user_id, created_at);
