-- alert 域基线（微服务化阶段 5：从共享 datanest 库 pg_dump --schema-only 生成，含当前全部 DDL/索引/约束/注释）
-- 后续演进脚本版本号必须大于 1.0.0（如 V1.1.0），紧凑单行风格
CREATE TABLE public.alert_history (
    id bigint NOT NULL,
    alert_rule_id bigint,
    object_type character varying(32) NOT NULL,
    object_id bigint NOT NULL,
    alert_type character varying(16) NOT NULL,
    recipients character varying(2000),
    sent_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    send_status character varying(16) DEFAULT 'SUCCESS'::character varying NOT NULL,
    rule_name character varying(255),
    quality_batch_id bigint,
    summary text,
    CONSTRAINT alert_history_alert_type_check CHECK (((alert_type)::text = ANY ((ARRAY['FAILURE'::character varying, 'TIMEOUT'::character varying, 'SUCCESS'::character varying])::text[])))
);
COMMENT ON TABLE public.alert_history IS '告警发送历史';
COMMENT ON COLUMN public.alert_history.alert_rule_id IS '告警规则 ID（可空，规则删除后保留历史）';
COMMENT ON COLUMN public.alert_history.object_type IS '对象类型：DAG / SYNC_JOB / COLLECT_TASK';
COMMENT ON COLUMN public.alert_history.object_id IS '对象 ID';
COMMENT ON COLUMN public.alert_history.alert_type IS '告警类型：FAILURE / TIMEOUT / SUCCESS';
COMMENT ON COLUMN public.alert_history.recipients IS '实际发送的邮箱列表，分号分隔';
COMMENT ON COLUMN public.alert_history.send_status IS '邮件发送状态：SUCCESS 发送成功 / FAILED 发送失败';
COMMENT ON COLUMN public.alert_history.quality_batch_id IS '关联的质量检查批次 ID（质量对象告警落库时写入，非质量告警为 NULL）';
COMMENT ON COLUMN public.alert_history.summary IS '质量批次告警聚合明细（每行一条命中规则：等级 + 规则名 + 详情；非质量告警为 NULL）';
CREATE SEQUENCE public.alert_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.alert_history_id_seq OWNED BY public.alert_history.id;
CREATE TABLE public.alert_rule (
    id bigint NOT NULL,
    object_type character varying(32) NOT NULL,
    object_name character varying(255),
    trigger_conditions character varying(255),
    timeout_minutes integer DEFAULT 30 NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    name character varying(255) NOT NULL,
    CONSTRAINT alert_rule_object_type_check CHECK (((object_type)::text = ANY ((ARRAY['DAG'::character varying, 'SYNC_JOB'::character varying, 'COLLECT_TASK'::character varying, 'QUALITY'::character varying])::text[])))
);
COMMENT ON TABLE public.alert_rule IS '通用告警规则表';
COMMENT ON COLUMN public.alert_rule.object_type IS '对象类型：DAG / SYNC_JOB / COLLECT_TASK';
COMMENT ON COLUMN public.alert_rule.object_name IS '对象名称冗余，便于列表展示';
COMMENT ON COLUMN public.alert_rule.trigger_conditions IS '触发条件 JSON 数组：FAILURE / TIMEOUT / SUCCESS';
COMMENT ON COLUMN public.alert_rule.timeout_minutes IS '超时阈值（分钟），默认 30';
COMMENT ON COLUMN public.alert_rule.enabled IS '是否启用：1 启用，0 关闭';
CREATE SEQUENCE public.alert_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.alert_rule_id_seq OWNED BY public.alert_rule.id;
CREATE TABLE public.alert_rule_object (
    id bigint NOT NULL,
    alert_rule_id bigint NOT NULL,
    object_type character varying(32) NOT NULL,
    object_id bigint NOT NULL,
    object_name character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE public.alert_rule_user (
    id bigint NOT NULL,
    alert_rule_id bigint NOT NULL,
    user_id bigint NOT NULL
);
COMMENT ON TABLE public.alert_rule_user IS '告警规则接收用户关联表';
COMMENT ON COLUMN public.alert_rule_user.alert_rule_id IS '告警规则 ID';
COMMENT ON COLUMN public.alert_rule_user.user_id IS '平台用户 ID（发送时反查 sys_user.email）';
CREATE SEQUENCE public.alert_rule_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.alert_rule_user_id_seq OWNED BY public.alert_rule_user.id;
CREATE TABLE public.dag_alert_config (
    id bigint NOT NULL,
    enabled smallint DEFAULT 0 NOT NULL,
    recipients character varying(1000),
    trigger_conditions character varying(255),
    timeout_minutes integer DEFAULT 30 NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    dag_id bigint
);
COMMENT ON TABLE public.dag_alert_config IS '全局 DAG 告警配置';
COMMENT ON COLUMN public.dag_alert_config.enabled IS '是否启用：1 启用，0 关闭';
COMMENT ON COLUMN public.dag_alert_config.recipients IS '收件人邮箱，分号分隔';
COMMENT ON COLUMN public.dag_alert_config.trigger_conditions IS '触发条件 JSON 数组：FAILURE / TIMEOUT / SUCCESS';
COMMENT ON COLUMN public.dag_alert_config.timeout_minutes IS '节点超时阈值（分钟）';
COMMENT ON COLUMN public.dag_alert_config.dag_id IS '所属 DAG ID；为空表示全局默认配置';
CREATE SEQUENCE public.dag_alert_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.dag_alert_config_id_seq OWNED BY public.dag_alert_config.id;
CREATE TABLE public.dag_alert_history (
    id bigint NOT NULL,
    execution_id bigint NOT NULL,
    node_id character varying(64),
    alert_type character varying(16) NOT NULL,
    recipients character varying(1000),
    sent_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT dag_alert_history_alert_type_check CHECK (((alert_type)::text = ANY ((ARRAY['FAILURE'::character varying, 'TIMEOUT'::character varying, 'SUCCESS'::character varying])::text[])))
);
COMMENT ON TABLE public.dag_alert_history IS 'DAG 告警发送记录（防重发）';
COMMENT ON COLUMN public.dag_alert_history.alert_type IS '告警类型：FAILURE / TIMEOUT / SUCCESS';
CREATE SEQUENCE public.dag_alert_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.dag_alert_history_id_seq OWNED BY public.dag_alert_history.id;
ALTER TABLE ONLY public.alert_history ALTER COLUMN id SET DEFAULT nextval('public.alert_history_id_seq'::regclass);
ALTER TABLE ONLY public.alert_rule ALTER COLUMN id SET DEFAULT nextval('public.alert_rule_id_seq'::regclass);
ALTER TABLE ONLY public.alert_rule_user ALTER COLUMN id SET DEFAULT nextval('public.alert_rule_user_id_seq'::regclass);
ALTER TABLE ONLY public.dag_alert_config ALTER COLUMN id SET DEFAULT nextval('public.dag_alert_config_id_seq'::regclass);
ALTER TABLE ONLY public.dag_alert_history ALTER COLUMN id SET DEFAULT nextval('public.dag_alert_history_id_seq'::regclass);
ALTER TABLE ONLY public.alert_history
    ADD CONSTRAINT alert_history_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.alert_rule_object
    ADD CONSTRAINT alert_rule_object_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.alert_rule_user
    ADD CONSTRAINT alert_rule_user_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_alert_config
    ADD CONSTRAINT dag_alert_config_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_alert_history
    ADD CONSTRAINT dag_alert_history_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.alert_rule_user
    ADD CONSTRAINT uk_alert_rule_user UNIQUE (alert_rule_id, user_id);
ALTER TABLE ONLY public.dag_alert_history
    ADD CONSTRAINT uk_dag_alert_history_en_type UNIQUE (execution_id, node_id, alert_type);
CREATE INDEX idx_alert_history_object ON public.alert_history USING btree (object_type, object_id);
CREATE INDEX idx_alert_history_quality_batch ON public.alert_history USING btree (quality_batch_id);
CREATE INDEX idx_alert_history_sent_at ON public.alert_history USING btree (sent_at);
CREATE INDEX idx_alert_rule_object_rule_id ON public.alert_rule_object USING btree (alert_rule_id);
CREATE INDEX idx_alert_rule_object_type ON public.alert_rule USING btree (object_type);
CREATE INDEX idx_alert_rule_object_type_id ON public.alert_rule_object USING btree (object_type, object_id);
CREATE INDEX idx_alert_rule_user_rule_id ON public.alert_rule_user USING btree (alert_rule_id);
CREATE INDEX idx_alert_rule_user_user_id ON public.alert_rule_user USING btree (user_id);
CREATE INDEX idx_dag_alert_config_dag_id ON public.dag_alert_config USING btree (dag_id);
CREATE INDEX idx_dag_alert_history_execution ON public.dag_alert_history USING btree (execution_id);
CREATE UNIQUE INDEX uk_alert_rule_name ON public.alert_rule USING btree (object_type, name);
ALTER TABLE ONLY public.alert_rule_object
    ADD CONSTRAINT fk_alert_rule_object_rule FOREIGN KEY (alert_rule_id) REFERENCES public.alert_rule(id) ON DELETE CASCADE;
