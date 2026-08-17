-- engineering 域基线（微服务化阶段 5：从共享 datanest 库 pg_dump --schema-only 生成，含当前全部 DDL/索引/约束/注释）
-- 后续演进脚本版本号必须大于 1.0.0（如 V1.1.0），紧凑单行风格
CREATE TABLE public.dag (
    id bigint NOT NULL,
    project_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    trigger_type character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    cron_expression character varying(100) DEFAULT NULL::character varying,
    schedule_enabled smallint DEFAULT 0 NOT NULL,
    max_parallelism integer DEFAULT 3 NOT NULL,
    status character varying(20) DEFAULT 'ENABLED'::character varying NOT NULL,
    ds_project_code bigint,
    ds_process_definition_id bigint,
    ds_process_definition_code bigint,
    ds_schedule_id bigint,
    release_state character varying(20) DEFAULT NULL::character varying,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone
);
COMMENT ON TABLE public.dag IS 'DAG 定义（一个 DAG 同步为 DS 一个 ProcessDefinition）';
COMMENT ON COLUMN public.dag.id IS '主键ID';
COMMENT ON COLUMN public.dag.project_id IS '所属项目ID（关联 dag_project.id）';
COMMENT ON COLUMN public.dag.name IS 'DAG 名（项目内唯一）';
COMMENT ON COLUMN public.dag.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN public.dag.cron_expression IS 'Cron 表达式（trigger_type=CRON 时必填）';
COMMENT ON COLUMN public.dag.schedule_enabled IS '调度是否启用：0-停止 1-运行';
COMMENT ON COLUMN public.dag.max_parallelism IS '最大并行节点数（默认 3）';
COMMENT ON COLUMN public.dag.status IS 'DAG 状态：ENABLED 启用，DISABLED 禁用';
COMMENT ON COLUMN public.dag.ds_project_code IS 'DS 项目 Code（关联 DolphinScheduler t_ds_project.code）';
COMMENT ON COLUMN public.dag.ds_process_definition_id IS 'DS 流程定义 ID';
COMMENT ON COLUMN public.dag.ds_process_definition_code IS 'DS 流程定义 Code（用于调用 DS API）';
COMMENT ON COLUMN public.dag.ds_schedule_id IS 'DS 调度 ID（CRON 触发时产生）';
COMMENT ON COLUMN public.dag.release_state IS 'DS 发布状态：OFFLINE / ONLINE';
COMMENT ON COLUMN public.dag.created_by IS '创建人ID';
COMMENT ON COLUMN public.dag.updated_by IS '更新人ID';
COMMENT ON COLUMN public.dag.created_at IS '创建时间';
COMMENT ON COLUMN public.dag.updated_at IS '更新时间';
CREATE TABLE public.dag_edge (
    id bigint NOT NULL,
    dag_id bigint NOT NULL,
    edge_id character varying(64) NOT NULL,
    source_node_id character varying(64) NOT NULL,
    target_node_id character varying(64) NOT NULL,
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.dag_edge IS 'DAG 边（节点依赖关系，source → target）';
COMMENT ON COLUMN public.dag_edge.id IS '主键ID';
COMMENT ON COLUMN public.dag_edge.dag_id IS '所属 DAG ID（关联 dag.id）';
COMMENT ON COLUMN public.dag_edge.edge_id IS '边 ID（DAG 内唯一）';
COMMENT ON COLUMN public.dag_edge.source_node_id IS '源节点 node_id';
COMMENT ON COLUMN public.dag_edge.target_node_id IS '目标节点 node_id';
COMMENT ON COLUMN public.dag_edge.created_by IS '创建人ID';
COMMENT ON COLUMN public.dag_edge.created_at IS '创建时间';
CREATE TABLE public.dag_execution (
    id bigint NOT NULL,
    dag_id bigint NOT NULL,
    ds_process_instance_id bigint,
    trigger_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    start_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    end_time timestamp without time zone,
    duration_ms bigint,
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    edge_snapshot text,
    error_message text,
    resolved_params text DEFAULT '{}'::jsonb
);
COMMENT ON TABLE public.dag_execution IS 'DAG 执行实例（一次 DAG 触发对应一条记录）';
COMMENT ON COLUMN public.dag_execution.id IS '主键ID';
COMMENT ON COLUMN public.dag_execution.dag_id IS '所属 DAG ID（关联 dag.id）';
COMMENT ON COLUMN public.dag_execution.ds_process_instance_id IS 'DS 流程实例 ID（用于查询 DS 状态）';
COMMENT ON COLUMN public.dag_execution.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN public.dag_execution.status IS '执行状态：RUNNING 运行中，SUCCESS 成功，FAILED 失败，TERMINATED 终止';
COMMENT ON COLUMN public.dag_execution.start_time IS '开始时间';
COMMENT ON COLUMN public.dag_execution.end_time IS '结束时间';
COMMENT ON COLUMN public.dag_execution.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN public.dag_execution.created_by IS '创建人ID';
COMMENT ON COLUMN public.dag_execution.created_at IS '创建时间';
COMMENT ON COLUMN public.dag_execution.edge_snapshot IS '创建执行实例时的 dag_edge JSON 快照（[{"source":"<nodeId>","target":"<nodeId>"},...]，无边时为 []，历史视图渲染边用）';
COMMENT ON COLUMN public.dag_execution.error_message IS '执行失败原因（如 DS 触发失败、工作流未上线等），用于历史列表/详情展示';
COMMENT ON COLUMN public.dag_execution.resolved_params IS '本次执行解析后的参数值（手动覆盖 + 默认值 + 系统变量），JSON 字符串存储';
CREATE TABLE public.dag_node (
    id bigint NOT NULL,
    dag_id bigint NOT NULL,
    node_id character varying(64) NOT NULL,
    node_name character varying(100) NOT NULL,
    node_type character varying(20) NOT NULL,
    position_x double precision DEFAULT 0,
    position_y double precision DEFAULT 0,
    config text,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    ds_task_code bigint,
    CONSTRAINT chk_dag_node_node_type CHECK (((node_type)::text = ANY ((ARRAY['SQL'::character varying, 'SYNC'::character varying, 'PYTHON'::character varying, 'CONDITION'::character varying, 'SUB_DAG'::character varying])::text[])))
);
COMMENT ON TABLE public.dag_node IS 'DAG 节点（SQL 任务 / SYNC 任务）';
COMMENT ON COLUMN public.dag_node.id IS '主键ID';
COMMENT ON COLUMN public.dag_node.dag_id IS '所属 DAG ID（关联 dag.id）';
COMMENT ON COLUMN public.dag_node.node_id IS '节点 ID（DAG 内唯一，前端生成的 UUID）';
COMMENT ON COLUMN public.dag_node.node_name IS '节点名称（用户可读）';
COMMENT ON COLUMN public.dag_node.node_type IS '节点类型：SQL SQL任务，SYNC 同步任务，PYTHON Python任务，CONDITION 条件分支，SUB_DAG 子DAG';
COMMENT ON COLUMN public.dag_node.position_x IS '画布 X 坐标';
COMMENT ON COLUMN public.dag_node.position_y IS '画布 Y 坐标';
COMMENT ON COLUMN public.dag_node.config IS '节点配置 JSON 字符串（String 存 JSON，决策 ADR-S3-005）。SQL: {type:SQL,sqlContent:str}；SYNC: {type:SYNC,syncJobId:num,syncJobName:str}';
COMMENT ON COLUMN public.dag_node.created_by IS '创建人ID';
COMMENT ON COLUMN public.dag_node.updated_by IS '更新人ID';
COMMENT ON COLUMN public.dag_node.created_at IS '创建时间';
COMMENT ON COLUMN public.dag_node.updated_at IS '更新时间';
COMMENT ON COLUMN public.dag_node.ds_task_code IS 'DolphinScheduler 任务定义 code（持久化，节点重命名后保持不变）';
CREATE TABLE public.dag_parameter (
    id bigint NOT NULL,
    dag_id bigint NOT NULL,
    param_name character varying(64) NOT NULL,
    param_type character varying(16) NOT NULL,
    default_value character varying(255),
    required smallint DEFAULT 1 NOT NULL,
    description character varying(500),
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT dag_parameter_param_type_check CHECK (((param_type)::text = ANY ((ARRAY['STRING'::character varying, 'NUMBER'::character varying, 'DATE'::character varying, 'BOOLEAN'::character varying])::text[])))
);
COMMENT ON TABLE public.dag_parameter IS 'DAG 自定义参数';
COMMENT ON COLUMN public.dag_parameter.param_name IS '参数名，DAG 内唯一';
COMMENT ON COLUMN public.dag_parameter.param_type IS '参数类型：STRING / NUMBER / DATE / BOOLEAN';
COMMENT ON COLUMN public.dag_parameter.default_value IS '默认值';
COMMENT ON COLUMN public.dag_parameter.required IS '手动触发时是否必填：1 必填，0 可选';
CREATE SEQUENCE public.dag_parameter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.dag_parameter_id_seq OWNED BY public.dag_parameter.id;
CREATE TABLE public.dag_project (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    ds_project_code bigint
);
COMMENT ON TABLE public.dag_project IS 'DAG 项目（DAG 命名空间，全局唯一）';
COMMENT ON COLUMN public.dag_project.id IS '主键ID';
COMMENT ON COLUMN public.dag_project.name IS '项目名（全局唯一）';
COMMENT ON COLUMN public.dag_project.description IS '项目描述';
COMMENT ON COLUMN public.dag_project.created_by IS '创建人ID';
COMMENT ON COLUMN public.dag_project.updated_by IS '更新人ID';
COMMENT ON COLUMN public.dag_project.created_at IS '创建时间';
COMMENT ON COLUMN public.dag_project.updated_at IS '更新时间';
COMMENT ON COLUMN public.dag_project.ds_project_code IS 'DS 项目 code（关联 DolphinScheduler t_ds_project.code）';
CREATE TABLE public.dag_version (
    id bigint NOT NULL,
    dag_id bigint NOT NULL,
    version_no integer NOT NULL,
    snapshot text NOT NULL,
    change_summary character varying(500),
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.dag_version IS 'DAG 版本快照';
COMMENT ON COLUMN public.dag_version.version_no IS '版本号：v1=1, v2=2...';
COMMENT ON COLUMN public.dag_version.snapshot IS '节点/边/参数 JSON 快照';
COMMENT ON COLUMN public.dag_version.change_summary IS '变更摘要';
CREATE SEQUENCE public.dag_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.dag_version_id_seq OWNED BY public.dag_version.id;
CREATE TABLE public.datasource_connection (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(20) NOT NULL,
    host character varying(255) NOT NULL,
    port integer NOT NULL,
    database_name character varying(100) NOT NULL,
    schema_name character varying(100) DEFAULT NULL::character varying,
    username character varying(100) NOT NULL,
    encrypted_password text NOT NULL,
    description text,
    status character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    last_test_time timestamp without time zone,
    error_message text,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    auto_collect_on_save smallint DEFAULT 0 NOT NULL
);
COMMENT ON TABLE public.datasource_connection IS '数据源连接信息';
COMMENT ON COLUMN public.datasource_connection.id IS '主键ID (雪花算法)';
COMMENT ON COLUMN public.datasource_connection.name IS '数据源名称';
COMMENT ON COLUMN public.datasource_connection.type IS '数据源类型：MYSQL、POSTGRESQL、DORIS';
COMMENT ON COLUMN public.datasource_connection.host IS '主机地址';
COMMENT ON COLUMN public.datasource_connection.port IS '端口';
COMMENT ON COLUMN public.datasource_connection.database_name IS '数据库名';
COMMENT ON COLUMN public.datasource_connection.schema_name IS 'Schema名（PostgreSQL必填）';
COMMENT ON COLUMN public.datasource_connection.username IS '用户名';
COMMENT ON COLUMN public.datasource_connection.encrypted_password IS 'AES-256-GCM加密后的密码';
COMMENT ON COLUMN public.datasource_connection.description IS '描述';
COMMENT ON COLUMN public.datasource_connection.status IS '连接状态：NORMAL 正常，ERROR 连接失败，OFFLINE 已删除仍有引用';
COMMENT ON COLUMN public.datasource_connection.last_test_time IS '最近测试时间';
COMMENT ON COLUMN public.datasource_connection.error_message IS '最近一次错误信息';
COMMENT ON COLUMN public.datasource_connection.created_by IS '创建人ID';
COMMENT ON COLUMN public.datasource_connection.updated_by IS '更新人ID';
COMMENT ON COLUMN public.datasource_connection.created_at IS '创建时间';
COMMENT ON COLUMN public.datasource_connection.updated_at IS '更新时间';
COMMENT ON COLUMN public.datasource_connection.auto_collect_on_save IS '保存后是否自动采集元数据（0-否 1-是）';
CREATE TABLE public.node_execution (
    id bigint NOT NULL,
    execution_id bigint NOT NULL,
    node_id character varying(64) NOT NULL,
    node_name character varying(100) NOT NULL,
    node_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'WAITING'::character varying NOT NULL,
    ds_task_instance_id bigint,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    duration_ms bigint,
    error_message text,
    output_info text,
    sync_job_id bigint,
    version integer DEFAULT 0 NOT NULL,
    sync_job_history_id bigint
);
COMMENT ON TABLE public.node_execution IS '节点执行实例（DAG 内每个节点一条）';
COMMENT ON COLUMN public.node_execution.id IS '主键ID';
COMMENT ON COLUMN public.node_execution.execution_id IS '所属 DAG 执行实例 ID（关联 dag_execution.id）';
COMMENT ON COLUMN public.node_execution.node_id IS '节点 node_id（关联 dag_node.node_id）';
COMMENT ON COLUMN public.node_execution.node_name IS '节点名称（冗余存储，便于历史快照）';
COMMENT ON COLUMN public.node_execution.node_type IS '节点类型：SQL / SYNC / PYTHON / CONDITION / SUB_DAG';
COMMENT ON COLUMN public.node_execution.status IS '执行状态：WAITING 等待，RUNNING 运行中，SUCCESS 成功，FAILED 失败，SKIPPED 跳过';
COMMENT ON COLUMN public.node_execution.ds_task_instance_id IS 'DS 任务实例 ID';
COMMENT ON COLUMN public.node_execution.start_time IS '开始时间';
COMMENT ON COLUMN public.node_execution.end_time IS '结束时间';
COMMENT ON COLUMN public.node_execution.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN public.node_execution.error_message IS '错误信息';
COMMENT ON COLUMN public.node_execution.output_info IS '输出信息（影响行数、创建的表名等）';
COMMENT ON COLUMN public.node_execution.sync_job_id IS '关联的同步任务 ID（SYNC 节点专用；用于 DagExecutionSyncService 反查 sync_job_history 同步终态）';
COMMENT ON COLUMN public.node_execution.sync_job_history_id IS 'SYNC 节点收尾时命中的 sync_job_history.id（用于查 sync_job_log 日志）';
CREATE TABLE public.node_execution_log (
    id bigint NOT NULL,
    execution_id bigint NOT NULL,
    node_id character varying(64) NOT NULL,
    level character varying(16) NOT NULL,
    message text NOT NULL,
    line_num integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT node_execution_log_level_check CHECK (((level)::text = ANY ((ARRAY['INFO'::character varying, 'WARN'::character varying, 'ERROR'::character varying])::text[])))
);
COMMENT ON TABLE public.node_execution_log IS 'DAG 节点执行日志';
COMMENT ON COLUMN public.node_execution_log.level IS '日志级别：INFO / WARN / ERROR';
COMMENT ON COLUMN public.node_execution_log.line_num IS '行号，用于排序';
CREATE SEQUENCE public.node_execution_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.node_execution_log_id_seq OWNED BY public.node_execution_log.id;
CREATE TABLE public.sync_job (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    source_datasource_id bigint NOT NULL,
    target_datasource_id bigint,
    source_database character varying(100) DEFAULT NULL::character varying,
    source_schema character varying(100) DEFAULT NULL::character varying,
    source_tables text DEFAULT '[]'::jsonb NOT NULL,
    sync_mode character varying(20) DEFAULT 'FULL'::character varying NOT NULL,
    trigger_type character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    cron_expression character varying(100) DEFAULT NULL::character varying,
    retry_times integer DEFAULT 0 NOT NULL,
    retry_interval integer DEFAULT 0 NOT NULL,
    field_mapping text DEFAULT '[]'::jsonb NOT NULL,
    status character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    schedule_enabled smallint DEFAULT 0 NOT NULL,
    xxl_job_id integer,
    description text,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    execution_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    target_database character varying(100),
    target_table character varying(100),
    next_execution_time timestamp without time zone,
    incremental_field character varying(100),
    last_execute_time timestamp without time zone,
    last_history_id bigint,
    source_tables_detail text DEFAULT '[]'::jsonb NOT NULL,
    read_rate_limit_mbps integer DEFAULT 0 NOT NULL,
    write_rate_limit_rows_per_second integer DEFAULT 0 NOT NULL,
    rate_limit_enabled smallint DEFAULT 0 NOT NULL
);
COMMENT ON TABLE public.sync_job IS '批量数据同步任务';
COMMENT ON COLUMN public.sync_job.id IS '主键ID';
COMMENT ON COLUMN public.sync_job.name IS '任务名称';
COMMENT ON COLUMN public.sync_job.source_datasource_id IS '源数据源ID';
COMMENT ON COLUMN public.sync_job.target_datasource_id IS '目标数据源ID（已废弃，目标端固定为内置Doris）';
COMMENT ON COLUMN public.sync_job.source_database IS '源数据库名';
COMMENT ON COLUMN public.sync_job.source_schema IS '源Schema名';
COMMENT ON COLUMN public.sync_job.source_tables IS '源表名数组';
COMMENT ON COLUMN public.sync_job.sync_mode IS '同步模式：FULL 全量，INCREMENTAL 增量';
COMMENT ON COLUMN public.sync_job.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN public.sync_job.cron_expression IS 'Cron表达式';
COMMENT ON COLUMN public.sync_job.retry_times IS '失败重试次数（0-3）';
COMMENT ON COLUMN public.sync_job.retry_interval IS '重试间隔分钟数（1-30）';
COMMENT ON COLUMN public.sync_job.field_mapping IS '字段映射配置JSON';
COMMENT ON COLUMN public.sync_job.status IS '调度状态：NORMAL 正常，PAUSED 暂停';
COMMENT ON COLUMN public.sync_job.schedule_enabled IS '调度是否启用（0-停止 1-运行）';
COMMENT ON COLUMN public.sync_job.xxl_job_id IS 'XXL-JOB 注册任务ID';
COMMENT ON COLUMN public.sync_job.description IS '任务描述';
COMMENT ON COLUMN public.sync_job.created_by IS '创建人ID';
COMMENT ON COLUMN public.sync_job.updated_by IS '更新人ID';
COMMENT ON COLUMN public.sync_job.created_at IS '创建时间';
COMMENT ON COLUMN public.sync_job.updated_at IS '更新时间';
COMMENT ON COLUMN public.sync_job.execution_status IS '执行状态：PENDING 未执行，RUNNING 运行中，SUCCESS 成功，FAILED 失败';
COMMENT ON COLUMN public.sync_job.target_database IS '目标 Doris 库名';
COMMENT ON COLUMN public.sync_job.target_table IS '目标 Doris 表名';
COMMENT ON COLUMN public.sync_job.next_execution_time IS 'Cron 任务下一次执行时间';
COMMENT ON COLUMN public.sync_job.incremental_field IS '增量同步字段';
COMMENT ON COLUMN public.sync_job.last_execute_time IS '最近执行时间';
COMMENT ON COLUMN public.sync_job.last_history_id IS '最近一次执行历史ID';
COMMENT ON COLUMN public.sync_job.source_tables_detail IS '多表结构化配置（TEXT，存 JSON 字符串，业务层 fastjson2 解析；Sprint3-Fix3 改自 jsonb）';
COMMENT ON COLUMN public.sync_job.read_rate_limit_mbps IS '读取速率限制（MB/s，0=不限制）。Sprint 3 增强：保护源库 IO';
COMMENT ON COLUMN public.sync_job.write_rate_limit_rows_per_second IS '写入速率限制（行/秒，0=不限制）。Sprint 3 增强：保护目标库 IO';
COMMENT ON COLUMN public.sync_job.rate_limit_enabled IS '限流总开关：0-关闭（按 read/write 字段生效），1-启用';
CREATE TABLE public.sync_job_history (
    id bigint NOT NULL,
    sync_job_id bigint NOT NULL,
    trigger_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    start_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    end_time timestamp without time zone,
    duration_ms bigint,
    source_rows bigint DEFAULT 0,
    target_rows bigint DEFAULT 0,
    error_message text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    parent_history_id bigint,
    retry_count integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp without time zone,
    dag_execution_id bigint,
    table_results text
);
COMMENT ON TABLE public.sync_job_history IS '批量数据同步执行历史';
COMMENT ON COLUMN public.sync_job_history.id IS '主键ID';
COMMENT ON COLUMN public.sync_job_history.sync_job_id IS '关联同步任务ID';
COMMENT ON COLUMN public.sync_job_history.trigger_type IS '触发方式：MANUAL/CRON';
COMMENT ON COLUMN public.sync_job_history.status IS '执行状态：RUNNING 运行中，SUCCESS 成功，FAILED 失败';
COMMENT ON COLUMN public.sync_job_history.start_time IS '开始时间';
COMMENT ON COLUMN public.sync_job_history.end_time IS '结束时间';
COMMENT ON COLUMN public.sync_job_history.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN public.sync_job_history.source_rows IS '源表读取行数';
COMMENT ON COLUMN public.sync_job_history.target_rows IS '目标表写入行数';
COMMENT ON COLUMN public.sync_job_history.error_message IS '错误信息';
COMMENT ON COLUMN public.sync_job_history.created_at IS '创建时间';
COMMENT ON COLUMN public.sync_job_history.parent_history_id IS '父历史记录ID，重试时指向来源执行记录';
COMMENT ON COLUMN public.sync_job_history.retry_count IS '当前执行链已发生的重试次数';
COMMENT ON COLUMN public.sync_job_history.next_retry_at IS '计划下次重试时间（仅记录）';
COMMENT ON COLUMN public.sync_job_history.dag_execution_id IS '由 DAG 编排触发时的 dag_execution.id；手动/定时触发为 NULL';
CREATE TABLE public.sync_job_log (
    id bigint NOT NULL,
    history_id bigint NOT NULL,
    sync_job_id bigint NOT NULL,
    level character varying(20) DEFAULT 'INFO'::character varying NOT NULL,
    message text NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    line_num integer DEFAULT 0,
    table_name character varying(100)
);
COMMENT ON TABLE public.sync_job_log IS '批量数据同步执行日志（Addax输出）';
COMMENT ON COLUMN public.sync_job_log.id IS '主键ID';
COMMENT ON COLUMN public.sync_job_log.history_id IS '关联执行历史ID';
COMMENT ON COLUMN public.sync_job_log.sync_job_id IS '关联同步任务ID';
COMMENT ON COLUMN public.sync_job_log.level IS '日志级别：INFO/WARN/ERROR';
COMMENT ON COLUMN public.sync_job_log.message IS '日志内容';
COMMENT ON COLUMN public.sync_job_log.created_at IS '创建时间';
COMMENT ON COLUMN public.sync_job_log.line_num IS 'Addax 日志原始行号';
ALTER TABLE ONLY public.dag_parameter ALTER COLUMN id SET DEFAULT nextval('public.dag_parameter_id_seq'::regclass);
ALTER TABLE ONLY public.dag_version ALTER COLUMN id SET DEFAULT nextval('public.dag_version_id_seq'::regclass);
ALTER TABLE ONLY public.node_execution_log ALTER COLUMN id SET DEFAULT nextval('public.node_execution_log_id_seq'::regclass);
ALTER TABLE ONLY public.dag_edge
    ADD CONSTRAINT dag_edge_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_execution
    ADD CONSTRAINT dag_execution_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_node
    ADD CONSTRAINT dag_node_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_parameter
    ADD CONSTRAINT dag_parameter_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag
    ADD CONSTRAINT dag_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_project
    ADD CONSTRAINT dag_project_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_version
    ADD CONSTRAINT dag_version_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.datasource_connection
    ADD CONSTRAINT datasource_connection_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.node_execution_log
    ADD CONSTRAINT node_execution_log_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.node_execution
    ADD CONSTRAINT node_execution_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sync_job_history
    ADD CONSTRAINT sync_job_history_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sync_job_log
    ADD CONSTRAINT sync_job_log_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT sync_job_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.dag_parameter
    ADD CONSTRAINT uk_dag_param_name UNIQUE (dag_id, param_name);
ALTER TABLE ONLY public.dag_version
    ADD CONSTRAINT uk_dag_version UNIQUE (dag_id, version_no);
CREATE INDEX idx_dag_ds_process_definition ON public.dag USING btree (ds_process_definition_code);
CREATE INDEX idx_dag_edge_dag_id ON public.dag_edge USING btree (dag_id);
CREATE INDEX idx_dag_edge_source_node_id ON public.dag_edge USING btree (source_node_id);
CREATE INDEX idx_dag_edge_target_node_id ON public.dag_edge USING btree (target_node_id);
CREATE INDEX idx_dag_execution_dag_id ON public.dag_execution USING btree (dag_id);
CREATE INDEX idx_dag_execution_ds_process_instance_id ON public.dag_execution USING btree (ds_process_instance_id);
CREATE INDEX idx_dag_execution_start_time ON public.dag_execution USING btree (start_time);
CREATE INDEX idx_dag_execution_status ON public.dag_execution USING btree (status);
CREATE INDEX idx_dag_node_dag_id ON public.dag_node USING btree (dag_id);
CREATE INDEX idx_dag_node_ds_task_code ON public.dag_node USING btree (ds_task_code);
CREATE INDEX idx_dag_node_node_type ON public.dag_node USING btree (node_type);
CREATE INDEX idx_dag_project_ds_project_code ON public.dag_project USING btree (ds_project_code);
CREATE INDEX idx_dag_project_id ON public.dag USING btree (project_id);
CREATE INDEX idx_dag_status ON public.dag USING btree (status);
CREATE INDEX idx_node_execution_ds_task_instance_id ON public.node_execution USING btree (ds_task_instance_id);
CREATE INDEX idx_node_execution_execution_id ON public.node_execution USING btree (execution_id);
CREATE INDEX idx_node_execution_log_en ON public.node_execution_log USING btree (execution_id, node_id);
CREATE INDEX idx_node_execution_status ON public.node_execution USING btree (status);
CREATE INDEX idx_node_execution_sync_job_history_id ON public.node_execution USING btree (sync_job_history_id);
CREATE INDEX idx_node_execution_sync_job_id ON public.node_execution USING btree (sync_job_id);
CREATE INDEX idx_sync_job_history_dag_execution_id ON public.sync_job_history USING btree (dag_execution_id);
CREATE INDEX idx_sync_job_history_parent_id ON public.sync_job_history USING btree (parent_history_id);
CREATE INDEX idx_sync_job_history_start_time ON public.sync_job_history USING btree (start_time);
CREATE INDEX idx_sync_job_history_status ON public.sync_job_history USING btree (status);
CREATE INDEX idx_sync_job_history_sync_job_id ON public.sync_job_history USING btree (sync_job_id);
CREATE INDEX idx_sync_job_history_sync_job_id_start_time ON public.sync_job_history USING btree (sync_job_id, start_time);
CREATE INDEX idx_sync_job_log_created_at ON public.sync_job_log USING btree (created_at);
CREATE INDEX idx_sync_job_log_hist_table ON public.sync_job_log USING btree (history_id, table_name, line_num);
CREATE INDEX idx_sync_job_log_history_id ON public.sync_job_log USING btree (history_id);
CREATE INDEX idx_sync_job_log_sync_job_id ON public.sync_job_log USING btree (sync_job_id);
CREATE INDEX idx_sync_job_rate_limit_enabled ON public.sync_job USING btree (rate_limit_enabled) WHERE (rate_limit_enabled = 1);
CREATE INDEX idx_sync_job_source_datasource_id ON public.sync_job USING btree (source_datasource_id);
CREATE INDEX idx_sync_job_status ON public.sync_job USING btree (status);
CREATE INDEX idx_sync_job_target_datasource_id ON public.sync_job USING btree (target_datasource_id);
CREATE UNIQUE INDEX uk_dag_edge_dag_id_edge_id ON public.dag_edge USING btree (dag_id, edge_id);
CREATE UNIQUE INDEX uk_dag_execution_running ON public.dag_execution USING btree (dag_id) WHERE ((status)::text = 'RUNNING'::text);
CREATE UNIQUE INDEX uk_dag_node_dag_id_node_id ON public.dag_node USING btree (dag_id, node_id);
CREATE UNIQUE INDEX uk_dag_project_id_name ON public.dag USING btree (project_id, name);
CREATE UNIQUE INDEX uk_dag_project_name ON public.dag_project USING btree (name);
CREATE UNIQUE INDEX uk_datasource_connection_name ON public.datasource_connection USING btree (name);
CREATE UNIQUE INDEX uk_sync_job_name ON public.sync_job USING btree (name);
