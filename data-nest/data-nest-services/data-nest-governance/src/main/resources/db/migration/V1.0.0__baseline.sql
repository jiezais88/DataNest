-- governance 域基线（微服务化阶段 5：从共享 datanest 库 pg_dump --schema-only 生成，含当前全部 DDL/索引/约束/注释）
-- 后续演进脚本版本号必须大于 1.0.0（如 V1.1.0），紧凑单行风格
CREATE TABLE public.asset_classification (
    id bigint NOT NULL,
    level character varying(20) NOT NULL,
    name character varying(100) NOT NULL,
    parent_id bigint,
    sort integer DEFAULT 0 NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone,
    updated_at timestamp without time zone
);
COMMENT ON TABLE public.asset_classification IS '数据资产分类体系（Sprint 7 F1，数据域→主题两级）';
COMMENT ON COLUMN public.asset_classification.level IS '层级：DOMAIN 数据域（一级）/ TOPIC 主题（二级）';
COMMENT ON COLUMN public.asset_classification.name IS '分类名称（同级唯一）';
COMMENT ON COLUMN public.asset_classification.parent_id IS '父分类 ID（TOPIC 指向 DOMAIN；DOMAIN 为 NULL）';
COMMENT ON COLUMN public.asset_classification.sort IS '同级排序号';
CREATE SEQUENCE public.asset_classification_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.asset_classification_id_seq OWNED BY public.asset_classification.id;
CREATE TABLE public.collect_change_detail (
    id bigint NOT NULL,
    history_id bigint NOT NULL,
    change_type character varying(30) NOT NULL,
    database_name character varying(100),
    schema_name character varying(100),
    table_name character varying(200),
    column_name character varying(200),
    old_value text,
    new_value text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.collect_change_detail IS '采集变更明细表';
COMMENT ON COLUMN public.collect_change_detail.history_id IS '关联的采集历史ID';
COMMENT ON COLUMN public.collect_change_detail.change_type IS '变更类型：ADDED_TABLE/DELETED_TABLE/MODIFIED_TABLE';
COMMENT ON COLUMN public.collect_change_detail.database_name IS '数据库名';
COMMENT ON COLUMN public.collect_change_detail.schema_name IS 'Schema名';
COMMENT ON COLUMN public.collect_change_detail.table_name IS '表名';
COMMENT ON COLUMN public.collect_change_detail.column_name IS '字段名（表级变更时为空）';
COMMENT ON COLUMN public.collect_change_detail.old_value IS '旧值';
COMMENT ON COLUMN public.collect_change_detail.new_value IS '新值';
CREATE TABLE public.collect_execution_log (
    id bigint NOT NULL,
    history_id bigint NOT NULL,
    task_id bigint NOT NULL,
    level character varying(20) DEFAULT 'INFO'::character varying NOT NULL,
    message text NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.collect_execution_log IS '元数据采集执行日志';
COMMENT ON COLUMN public.collect_execution_log.id IS '主键ID';
COMMENT ON COLUMN public.collect_execution_log.history_id IS '关联历史记录ID';
COMMENT ON COLUMN public.collect_execution_log.task_id IS '关联任务ID';
COMMENT ON COLUMN public.collect_execution_log.level IS '日志级别：INFO/WARN/ERROR';
COMMENT ON COLUMN public.collect_execution_log.message IS '日志内容';
COMMENT ON COLUMN public.collect_execution_log.created_at IS '创建时间';
CREATE TABLE public.collect_history (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    task_name character varying(100) NOT NULL,
    datasource_id bigint NOT NULL,
    trigger_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ended_at timestamp without time zone,
    duration_ms bigint,
    db_count integer DEFAULT 0 NOT NULL,
    table_count integer DEFAULT 0 NOT NULL,
    column_count integer DEFAULT 0 NOT NULL,
    added_table_count integer DEFAULT 0 NOT NULL,
    updated_table_count integer DEFAULT 0 NOT NULL,
    deleted_table_count integer DEFAULT 0 NOT NULL,
    added_column_count integer DEFAULT 0 NOT NULL,
    updated_column_count integer DEFAULT 0 NOT NULL,
    deleted_column_count integer DEFAULT 0 NOT NULL,
    error_message text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.collect_history IS '元数据采集历史';
COMMENT ON COLUMN public.collect_history.id IS '主键ID';
COMMENT ON COLUMN public.collect_history.task_id IS '关联任务ID';
COMMENT ON COLUMN public.collect_history.task_name IS '任务名称（冗余）';
COMMENT ON COLUMN public.collect_history.datasource_id IS '关联数据源ID';
COMMENT ON COLUMN public.collect_history.trigger_type IS '触发方式：MANUAL/CRON';
COMMENT ON COLUMN public.collect_history.status IS '执行状态：RUNNING 运行中，SUCCESS 成功，FAILED 失败';
COMMENT ON COLUMN public.collect_history.started_at IS '开始时间';
COMMENT ON COLUMN public.collect_history.ended_at IS '结束时间';
COMMENT ON COLUMN public.collect_history.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN public.collect_history.db_count IS '采集库/Schema 数';
COMMENT ON COLUMN public.collect_history.table_count IS '采集表数';
COMMENT ON COLUMN public.collect_history.column_count IS '采集字段数';
COMMENT ON COLUMN public.collect_history.added_table_count IS '新增表数';
COMMENT ON COLUMN public.collect_history.updated_table_count IS '更新表数';
COMMENT ON COLUMN public.collect_history.deleted_table_count IS '删除表数';
COMMENT ON COLUMN public.collect_history.added_column_count IS '新增字段数';
COMMENT ON COLUMN public.collect_history.updated_column_count IS '更新字段数';
COMMENT ON COLUMN public.collect_history.deleted_column_count IS '删除字段数';
COMMENT ON COLUMN public.collect_history.error_message IS '错误信息';
COMMENT ON COLUMN public.collect_history.created_at IS '创建时间';
CREATE TABLE public.collect_task (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    datasource_id bigint NOT NULL,
    datasource_name character varying(100) NOT NULL,
    scope text DEFAULT '[]'::text NOT NULL,
    collect_mode character varying(20) DEFAULT 'FULL'::character varying NOT NULL,
    trigger_type character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    cron_expression character varying(100) DEFAULT NULL::character varying,
    status character varying(20) DEFAULT 'NEVER_EXECUTED'::character varying NOT NULL,
    last_execute_time timestamp without time zone,
    last_history_id bigint,
    description text,
    xxl_job_id integer,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    schedule_enabled smallint DEFAULT 0 NOT NULL,
    next_execution_time timestamp without time zone
);
COMMENT ON TABLE public.collect_task IS '元数据采集任务';
COMMENT ON COLUMN public.collect_task.id IS '主键ID (雪花算法)';
COMMENT ON COLUMN public.collect_task.name IS '任务名称';
COMMENT ON COLUMN public.collect_task.datasource_id IS '关联数据源ID';
COMMENT ON COLUMN public.collect_task.datasource_name IS '数据源名称（冗余）';
COMMENT ON COLUMN public.collect_task.scope IS '采集范围：库/Schema 名称数组';
COMMENT ON COLUMN public.collect_task.collect_mode IS '采集模式：FULL 全量，FULL_INCREMENT 全量+增量';
COMMENT ON COLUMN public.collect_task.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN public.collect_task.cron_expression IS 'Cron 表达式，trigger_type=CRON 时必填';
COMMENT ON COLUMN public.collect_task.status IS '任务状态：NEVER_EXECUTED 未执行，RUNNING 运行中，SUCCESS 成功，FAILED 失败，TERMINATED 已终止';
COMMENT ON COLUMN public.collect_task.last_execute_time IS '最近执行时间';
COMMENT ON COLUMN public.collect_task.last_history_id IS '最近一次历史记录ID';
COMMENT ON COLUMN public.collect_task.description IS '任务描述';
COMMENT ON COLUMN public.collect_task.xxl_job_id IS 'XXL-JOB 注册任务 ID';
COMMENT ON COLUMN public.collect_task.created_by IS '创建人ID';
COMMENT ON COLUMN public.collect_task.updated_by IS '更新人ID';
COMMENT ON COLUMN public.collect_task.created_at IS '创建时间';
COMMENT ON COLUMN public.collect_task.updated_at IS '更新时间';
COMMENT ON COLUMN public.collect_task.schedule_enabled IS '调度是否启用（仅 CRON 任务有效，0-停止 1-运行）';
COMMENT ON COLUMN public.collect_task.next_execution_time IS 'Cron 任务下一次执行时间';
CREATE TABLE public.compliance_check_result (
    id bigint NOT NULL,
    standard_id bigint,
    object_type character varying(20) NOT NULL,
    table_id bigint,
    column_id bigint,
    object_name character varying(255) NOT NULL,
    actual_value character varying(255) DEFAULT NULL::character varying,
    expected_value character varying(255) DEFAULT NULL::character varying,
    is_compliant smallint DEFAULT 0 NOT NULL,
    checked_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    datasource_id bigint,
    database_name character varying(255) DEFAULT NULL::character varying,
    schema_name character varying(255) DEFAULT NULL::character varying,
    violation_type character varying(20) DEFAULT NULL::character varying,
    standard_name character varying(100) DEFAULT NULL::character varying,
    object_path character varying(500) DEFAULT NULL::character varying,
    applicable_standards text,
    ignored smallint DEFAULT 0 NOT NULL,
    ignored_at timestamp without time zone,
    ignored_by bigint
);
COMMENT ON TABLE public.compliance_check_result IS '合规检查结果';
COMMENT ON COLUMN public.compliance_check_result.id IS '主键ID';
COMMENT ON COLUMN public.compliance_check_result.standard_id IS '命中的命名规范ID，未命中时可为空';
COMMENT ON COLUMN public.compliance_check_result.object_type IS '对象类型：TABLE 表，COLUMN 字段';
COMMENT ON COLUMN public.compliance_check_result.table_id IS '关联元数据表ID';
COMMENT ON COLUMN public.compliance_check_result.column_id IS '关联元数据字段ID';
COMMENT ON COLUMN public.compliance_check_result.object_name IS '对象名称（表名或字段名）';
COMMENT ON COLUMN public.compliance_check_result.actual_value IS '实际值（字段类型等）';
COMMENT ON COLUMN public.compliance_check_result.expected_value IS '期望值（允许的字段类型等）';
COMMENT ON COLUMN public.compliance_check_result.is_compliant IS '是否合规（0-不合规 1-合规）';
COMMENT ON COLUMN public.compliance_check_result.checked_at IS '检查时间';
COMMENT ON COLUMN public.compliance_check_result.datasource_id IS '数据源ID';
COMMENT ON COLUMN public.compliance_check_result.database_name IS '数据库名';
COMMENT ON COLUMN public.compliance_check_result.schema_name IS 'Schema名';
COMMENT ON COLUMN public.compliance_check_result.violation_type IS '违规类型：NAMING 命名不合规，TYPE 字段类型不合规';
COMMENT ON COLUMN public.compliance_check_result.standard_name IS '命中的命名规范名称，未命中时可为空';
COMMENT ON COLUMN public.compliance_check_result.object_path IS '检查对象路径，如 db.schema.table.column';
COMMENT ON COLUMN public.compliance_check_result.applicable_standards IS '本次检查涉及的相关规范列表（JSON），用于结果展示';
COMMENT ON COLUMN public.compliance_check_result.ignored IS '是否已忽略（0-未忽略 1-已忽略）';
COMMENT ON COLUMN public.compliance_check_result.ignored_at IS '忽略时间';
COMMENT ON COLUMN public.compliance_check_result.ignored_by IS '忽略操作人ID';
CREATE TABLE public.field_type_standard (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    category character varying(50) DEFAULT NULL::character varying,
    allowed_types text DEFAULT '[]'::jsonb NOT NULL,
    description text,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone
);
COMMENT ON TABLE public.field_type_standard IS '字段类型标准';
COMMENT ON COLUMN public.field_type_standard.id IS '主键ID';
COMMENT ON COLUMN public.field_type_standard.name IS '标准名称';
COMMENT ON COLUMN public.field_type_standard.category IS '分类（如：数值、字符串、时间）';
COMMENT ON COLUMN public.field_type_standard.allowed_types IS '允许的字段类型数组';
COMMENT ON COLUMN public.field_type_standard.description IS '描述';
COMMENT ON COLUMN public.field_type_standard.created_by IS '创建人ID';
COMMENT ON COLUMN public.field_type_standard.updated_by IS '更新人ID';
COMMENT ON COLUMN public.field_type_standard.created_at IS '创建时间';
COMMENT ON COLUMN public.field_type_standard.updated_at IS '更新时间';
CREATE TABLE public.lineage_record (
    id bigint NOT NULL,
    source_table character varying(500),
    target_table character varying(500) NOT NULL,
    dag_id bigint,
    dag_name character varying(255),
    node_id character varying(64),
    node_name character varying(255),
    execution_id bigint,
    lineage_type character varying(16) DEFAULT 'SQL'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    source_column character varying(255),
    target_column character varying(255)
);
COMMENT ON TABLE public.lineage_record IS '表级血缘记录';
COMMENT ON COLUMN public.lineage_record.source_table IS '源表名';
COMMENT ON COLUMN public.lineage_record.target_table IS '目标表名';
COMMENT ON COLUMN public.lineage_record.lineage_type IS '血缘类型：SQL / SYNC / PYTHON';
COMMENT ON COLUMN public.lineage_record.source_column IS '源字段，字段级血缘时使用；表级血缘为 NULL';
COMMENT ON COLUMN public.lineage_record.target_column IS '目标字段，字段级血缘时使用；表级血缘为 NULL';
CREATE SEQUENCE public.lineage_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.lineage_record_id_seq OWNED BY public.lineage_record.id;
CREATE TABLE public.metadata_column (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    column_name character varying(100) NOT NULL,
    data_type character varying(100) NOT NULL,
    column_comment text,
    manual_comment text,
    nullable boolean DEFAULT true,
    column_default text,
    ordinal_position integer DEFAULT 0 NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_collect_history_id bigint,
    remark text,
    source_type character varying(20) DEFAULT 'EXTERNAL'::character varying NOT NULL,
    source_status character varying(20) DEFAULT 'ONLINE'::character varying NOT NULL
);
COMMENT ON TABLE public.metadata_column IS '元数据字段';
COMMENT ON COLUMN public.metadata_column.id IS '主键ID';
COMMENT ON COLUMN public.metadata_column.table_id IS '关联元数据表ID';
COMMENT ON COLUMN public.metadata_column.column_name IS '字段名';
COMMENT ON COLUMN public.metadata_column.data_type IS '字段类型';
COMMENT ON COLUMN public.metadata_column.column_comment IS '源库字段注释';
COMMENT ON COLUMN public.metadata_column.manual_comment IS '人工编辑的字段注释，增量采集不覆盖';
COMMENT ON COLUMN public.metadata_column.nullable IS '是否允许为空';
COMMENT ON COLUMN public.metadata_column.column_default IS '默认值';
COMMENT ON COLUMN public.metadata_column.ordinal_position IS '字段顺序';
COMMENT ON COLUMN public.metadata_column.created_by IS '创建人ID';
COMMENT ON COLUMN public.metadata_column.updated_by IS '更新人ID';
COMMENT ON COLUMN public.metadata_column.created_at IS '创建时间';
COMMENT ON COLUMN public.metadata_column.updated_at IS '更新时间';
COMMENT ON COLUMN public.metadata_column.last_collect_history_id IS '最近一次采集历史记录ID';
COMMENT ON COLUMN public.metadata_column.remark IS '业务口径、枚举值说明等补充信息，可人工编辑';
COMMENT ON COLUMN public.metadata_column.source_type IS '元数据来源：BUILTIN_DORIS 内置Doris，EXTERNAL 外部数据源';
COMMENT ON COLUMN public.metadata_column.source_status IS '源状态：ONLINE 在线，OFFLINE 源已删除';
CREATE TABLE public.metadata_table (
    id bigint NOT NULL,
    datasource_id bigint NOT NULL,
    database_name character varying(100) NOT NULL,
    schema_name character varying(100) DEFAULT NULL::character varying,
    table_name character varying(100) NOT NULL,
    table_comment text,
    manual_comment text,
    source_status character varying(20) DEFAULT 'ONLINE'::character varying NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_collect_history_id bigint,
    source_type character varying(20) DEFAULT 'EXTERNAL'::character varying NOT NULL,
    task_source_type character varying(32),
    source_dag_id bigint,
    source_dag_name character varying(255),
    source_node_id character varying(64),
    source_node_name character varying(255),
    data_domain character varying(100),
    data_topic character varying(100),
    owner_user_id bigint
);
COMMENT ON TABLE public.metadata_table IS '元数据表';
COMMENT ON COLUMN public.metadata_table.id IS '主键ID';
COMMENT ON COLUMN public.metadata_table.datasource_id IS '关联数据源ID';
COMMENT ON COLUMN public.metadata_table.database_name IS '数据库名';
COMMENT ON COLUMN public.metadata_table.schema_name IS 'Schema名';
COMMENT ON COLUMN public.metadata_table.table_name IS '表名';
COMMENT ON COLUMN public.metadata_table.table_comment IS '源库表注释';
COMMENT ON COLUMN public.metadata_table.manual_comment IS '人工编辑的表注释，增量采集不覆盖';
COMMENT ON COLUMN public.metadata_table.source_status IS '源状态：ONLINE 在线，OFFLINE 源已删除';
COMMENT ON COLUMN public.metadata_table.created_by IS '创建人ID';
COMMENT ON COLUMN public.metadata_table.updated_by IS '更新人ID';
COMMENT ON COLUMN public.metadata_table.created_at IS '创建时间';
COMMENT ON COLUMN public.metadata_table.updated_at IS '更新时间';
COMMENT ON COLUMN public.metadata_table.last_collect_history_id IS '最近一次采集历史记录ID';
COMMENT ON COLUMN public.metadata_table.source_type IS '元数据来源：BUILTIN_DORIS 内置Doris，EXTERNAL 外部数据源';
COMMENT ON COLUMN public.metadata_table.task_source_type IS '任务来源类型：SQL / SYNC / PYTHON';
COMMENT ON COLUMN public.metadata_table.source_dag_id IS '来源 DAG ID';
COMMENT ON COLUMN public.metadata_table.source_dag_name IS '来源 DAG 名称';
COMMENT ON COLUMN public.metadata_table.source_node_id IS '来源节点 ID';
COMMENT ON COLUMN public.metadata_table.source_node_name IS '来源节点名称';
COMMENT ON COLUMN public.metadata_table.data_domain IS '数据域（一级分类名，冗余存名称便于展示，Sprint 7 F1）';
COMMENT ON COLUMN public.metadata_table.data_topic IS '主题（二级分类名，冗余存名称，Sprint 7 F1）';
COMMENT ON COLUMN public.metadata_table.owner_user_id IS '表负责人用户 ID（关联 sys_user.id，Sprint 7 F1）';
CREATE TABLE public.naming_standard (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    applies_to character varying(20) NOT NULL,
    rule_type character varying(20) NOT NULL,
    rule_value character varying(255) NOT NULL,
    target_standard_id bigint,
    priority integer DEFAULT 0 NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    description text,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone
);
COMMENT ON TABLE public.naming_standard IS '命名规范';
COMMENT ON COLUMN public.naming_standard.id IS '主键ID';
COMMENT ON COLUMN public.naming_standard.name IS '规范名称';
COMMENT ON COLUMN public.naming_standard.applies_to IS '适用对象：TABLE 表名，COLUMN 字段名';
COMMENT ON COLUMN public.naming_standard.rule_type IS '规则类型：PREFIX 前缀，SUFFIX 后缀，REGEX 正则';
COMMENT ON COLUMN public.naming_standard.rule_value IS '规则值';
COMMENT ON COLUMN public.naming_standard.target_standard_id IS '关联的字段类型标准ID';
COMMENT ON COLUMN public.naming_standard.priority IS '优先级，数字越大越优先';
COMMENT ON COLUMN public.naming_standard.enabled IS '是否启用（0-禁用 1-启用）';
COMMENT ON COLUMN public.naming_standard.description IS '描述';
COMMENT ON COLUMN public.naming_standard.created_by IS '创建人ID';
COMMENT ON COLUMN public.naming_standard.updated_by IS '更新人ID';
COMMENT ON COLUMN public.naming_standard.created_at IS '创建时间';
COMMENT ON COLUMN public.naming_standard.updated_at IS '更新时间';
CREATE TABLE public.quality_check_batch (
    id bigint NOT NULL,
    job_id bigint,
    job_name character varying(100),
    trigger_type character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ended_at timestamp without time zone,
    duration_ms bigint,
    error_message text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    alert_sent smallint DEFAULT 0 NOT NULL
);
COMMENT ON TABLE public.quality_check_batch IS '质量检查批次（Sprint 8 执行层）';
COMMENT ON COLUMN public.quality_check_batch.job_id IS '质量任务 ID（单规则执行为空）';
COMMENT ON COLUMN public.quality_check_batch.job_name IS '任务名称快照';
COMMENT ON COLUMN public.quality_check_batch.trigger_type IS '触发方式：MANUAL / SCHEDULED / AUTO_TRIGGER';
COMMENT ON COLUMN public.quality_check_batch.status IS '批次状态：RUNNING / SUCCESS / PARTIAL_FAILED / FAILED';
COMMENT ON COLUMN public.quality_check_batch.started_at IS '开始时间';
COMMENT ON COLUMN public.quality_check_batch.ended_at IS '结束时间';
COMMENT ON COLUMN public.quality_check_batch.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN public.quality_check_batch.error_message IS '整体错误信息（非规则级）';
COMMENT ON COLUMN public.quality_check_batch.alert_sent IS '合并告警是否已发送：1 已发送，0 未发送（幂等防重发）';
CREATE SEQUENCE public.quality_check_batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_check_batch_id_seq OWNED BY public.quality_check_batch.id;
CREATE TABLE public.quality_check_detail (
    id bigint NOT NULL,
    batch_id bigint NOT NULL,
    rule_id bigint NOT NULL,
    rule_name character varying(100),
    rule_type character varying(20),
    table_id bigint,
    result_metric character varying(50),
    result_value numeric(20,6),
    success smallint DEFAULT 0 NOT NULL,
    error_message text,
    executed_sql text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    result_level character varying(20)
);
COMMENT ON TABLE public.quality_check_detail IS '质量检查规则明细（Sprint 8 执行层）';
COMMENT ON COLUMN public.quality_check_detail.batch_id IS '所属批次';
COMMENT ON COLUMN public.quality_check_detail.rule_id IS '质量规则 ID';
COMMENT ON COLUMN public.quality_check_detail.rule_name IS '规则名称快照';
COMMENT ON COLUMN public.quality_check_detail.rule_type IS '规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL';
COMMENT ON COLUMN public.quality_check_detail.table_id IS '目标表 metadata_table.id';
COMMENT ON COLUMN public.quality_check_detail.result_metric IS '结果指标名';
COMMENT ON COLUMN public.quality_check_detail.result_value IS '执行结果值（DECIMAL，仅记录结果值不做分级）';
COMMENT ON COLUMN public.quality_check_detail.success IS '规则执行是否成功：1 成功，0 失败';
COMMENT ON COLUMN public.quality_check_detail.error_message IS '规则执行错误信息';
COMMENT ON COLUMN public.quality_check_detail.executed_sql IS '实际执行的校验 SQL';
COMMENT ON COLUMN public.quality_check_detail.result_level IS '分级判定：PASS 通过 / WARNING 警告 / SEVERE 严重 / UNAVAILABLE 不可用（执行失败）';
CREATE SEQUENCE public.quality_check_detail_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_check_detail_id_seq OWNED BY public.quality_check_detail.id;
CREATE TABLE public.quality_job (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    datasource_id bigint,
    enabled smallint DEFAULT 1 NOT NULL,
    scheduled_enabled smallint DEFAULT 0 NOT NULL,
    cron character varying(64),
    auto_trigger_enabled smallint DEFAULT 0 NOT NULL,
    auto_trigger_object_type character varying(30),
    auto_trigger_object_id bigint,
    alert_level character varying(20) DEFAULT 'SEVERE_WARNING'::character varying NOT NULL,
    last_trigger_at timestamp without time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    xxl_job_id integer,
    timeout_minutes integer
);
COMMENT ON TABLE public.quality_job IS '质量任务（Sprint 6 配置层）';
COMMENT ON COLUMN public.quality_job.name IS '任务名称（唯一）';
COMMENT ON COLUMN public.quality_job.datasource_id IS '可选，数据源范围（用于选表过滤）';
COMMENT ON COLUMN public.quality_job.scheduled_enabled IS '是否开定时调度（D1）';
COMMENT ON COLUMN public.quality_job.cron IS '定时 cron（scheduled_enabled=1 时必填）';
COMMENT ON COLUMN public.quality_job.auto_trigger_enabled IS '是否任务完成自动触发（D1）';
COMMENT ON COLUMN public.quality_job.auto_trigger_object_type IS '自动触发绑定对象类型：DAG_NODE / SYNC_JOB / COLLECT_TASK';
COMMENT ON COLUMN public.quality_job.alert_level IS '告警触发等级：SEVERE_ONLY / SEVERE_WARNING';
COMMENT ON COLUMN public.quality_job.last_trigger_at IS '最近一次触发时间（防重 R6）';
COMMENT ON COLUMN public.quality_job.xxl_job_id IS '定时调度 XXL-JOB 任务 ID（注册到 data-nest-worker 组，带自身 cron）';
CREATE SEQUENCE public.quality_job_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_job_id_seq OWNED BY public.quality_job.id;
CREATE TABLE public.quality_job_rule (
    id bigint NOT NULL,
    job_id bigint NOT NULL,
    rule_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.quality_job_rule IS '质量任务-质量规则 多对多关联表（Sprint 7 规则独立化）';
COMMENT ON COLUMN public.quality_job_rule.job_id IS '质量任务 ID';
COMMENT ON COLUMN public.quality_job_rule.rule_id IS '质量规则 ID';
CREATE SEQUENCE public.quality_job_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_job_rule_id_seq OWNED BY public.quality_job_rule.id;
CREATE TABLE public.quality_rule (
    id bigint NOT NULL,
    job_id bigint,
    template_id bigint,
    name character varying(100) NOT NULL,
    type character varying(20) NOT NULL,
    table_id bigint,
    column_name character varying(128),
    check_field smallint DEFAULT 0 NOT NULL,
    sql_expression text,
    warning_threshold numeric(20,6),
    severe_threshold numeric(20,6),
    result_metric character varying(50),
    weight integer DEFAULT 1 NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    range_min numeric(20,6),
    range_max numeric(20,6)
);
COMMENT ON TABLE public.quality_rule IS '质量规则实例（Sprint 6 配置层，挂任务下）';
COMMENT ON COLUMN public.quality_rule.job_id IS '所属质量任务（可空；规则独立创建后通过 quality_job_rule 关联任务）';
COMMENT ON COLUMN public.quality_rule.template_id IS '来源模板（可空，自定义 SQL 也记）';
COMMENT ON COLUMN public.quality_rule.name IS '规则名称';
COMMENT ON COLUMN public.quality_rule.type IS '规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL';
COMMENT ON COLUMN public.quality_rule.table_id IS '目标表 metadata_table.id';
COMMENT ON COLUMN public.quality_rule.column_name IS '检查字段（唯一性/值域必填；完整性可空）';
COMMENT ON COLUMN public.quality_rule.check_field IS '是否按字段检查（完整性填字段时=1，整表=0）';
COMMENT ON COLUMN public.quality_rule.sql_expression IS '实际校验 SQL（执行时动态生成，本次不落库；自定义 SQL 除外）';
COMMENT ON COLUMN public.quality_rule.warning_threshold IS '警告阈值（执行结果 ≥ 此值 → 警告）';
COMMENT ON COLUMN public.quality_rule.severe_threshold IS '严重阈值（执行结果 ≥ 此值 → 严重）';
COMMENT ON COLUMN public.quality_rule.result_metric IS '结果指标名';
COMMENT ON COLUMN public.quality_rule.weight IS '权重（评分加权，默认 1）';
COMMENT ON COLUMN public.quality_rule.range_min IS '值域下界（RANGE 类型专用，SQL 模板 {min} 占位符来源；其余类型为 NULL）';
COMMENT ON COLUMN public.quality_rule.range_max IS '值域上界（RANGE 类型专用，SQL 模板 {max} 占位符来源；其余类型为 NULL）';
CREATE SEQUENCE public.quality_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_rule_id_seq OWNED BY public.quality_rule.id;
CREATE TABLE public.quality_rule_template (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(20) NOT NULL,
    description character varying(500),
    sql_template text,
    result_metric character varying(50),
    builtin smallint DEFAULT 0 NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    created_by bigint,
    updated_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT quality_rule_template_type_check CHECK (((type)::text = ANY ((ARRAY['COMPLETENESS'::character varying, 'UNIQUENESS'::character varying, 'RANGE'::character varying, 'CUSTOM_SQL'::character varying])::text[])))
);
COMMENT ON TABLE public.quality_rule_template IS '质量规则模板库（D3）';
COMMENT ON COLUMN public.quality_rule_template.name IS '模板名称（唯一）';
COMMENT ON COLUMN public.quality_rule_template.type IS '模板类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL';
COMMENT ON COLUMN public.quality_rule_template.description IS '模板说明';
COMMENT ON COLUMN public.quality_rule_template.sql_template IS '校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等';
COMMENT ON COLUMN public.quality_rule_template.result_metric IS '结果指标名，如 null_rate / duplicate_count / out_of_range_rate';
COMMENT ON COLUMN public.quality_rule_template.builtin IS '是否内置：1 内置，0 自定义';
COMMENT ON COLUMN public.quality_rule_template.enabled IS '是否启用：1 启用，0 停用';
CREATE SEQUENCE public.quality_rule_template_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_rule_template_id_seq OWNED BY public.quality_rule_template.id;
CREATE TABLE public.quality_score (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    table_name character varying(255),
    datasource_id bigint,
    score numeric(5,2),
    health_level character varying(20),
    pass_rules integer DEFAULT 0 NOT NULL,
    warning_rules integer DEFAULT 0 NOT NULL,
    severe_rules integer DEFAULT 0 NOT NULL,
    last_checked_at timestamp without time zone,
    updated_at timestamp without time zone
);
COMMENT ON TABLE public.quality_score IS '表级质量评分（Sprint 6 NG8，一张表一行最新评分）';
COMMENT ON COLUMN public.quality_score.table_id IS '目标表 metadata_table.id';
COMMENT ON COLUMN public.quality_score.table_name IS '库名.表名';
COMMENT ON COLUMN public.quality_score.datasource_id IS '数据源';
COMMENT ON COLUMN public.quality_score.score IS '0-100 分';
COMMENT ON COLUMN public.quality_score.health_level IS '健康度：EXCELLENT/GOOD/WARNING/BAD';
COMMENT ON COLUMN public.quality_score.pass_rules IS '最近一次通过规则数';
COMMENT ON COLUMN public.quality_score.warning_rules IS '最近一次警告规则数';
COMMENT ON COLUMN public.quality_score.severe_rules IS '最近一次严重规则数';
COMMENT ON COLUMN public.quality_score.last_checked_at IS '最近检查时间';
COMMENT ON COLUMN public.quality_score.updated_at IS '评分更新时间';
CREATE TABLE public.quality_score_config (
    id bigint NOT NULL,
    warning_deduct integer DEFAULT 10 NOT NULL,
    severe_deduct integer DEFAULT 30 NOT NULL,
    bad_threshold integer DEFAULT 60 NOT NULL,
    updated_by bigint,
    updated_at timestamp without time zone
);
COMMENT ON TABLE public.quality_score_config IS '质量评分全局配置（Sprint 6 NG8，单行配置）';
COMMENT ON COLUMN public.quality_score_config.warning_deduct IS '警告规则每权重扣分分值';
COMMENT ON COLUMN public.quality_score_config.severe_deduct IS '严重规则每权重扣分分值';
COMMENT ON COLUMN public.quality_score_config.bad_threshold IS '低分区阈值：评分 < 此值 → 健康度「差」；存在严重规则强制压至低分区';
COMMENT ON COLUMN public.quality_score_config.updated_by IS '最近修改人';
COMMENT ON COLUMN public.quality_score_config.updated_at IS '最近修改时间';
CREATE SEQUENCE public.quality_score_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_score_config_id_seq OWNED BY public.quality_score_config.id;
CREATE SEQUENCE public.quality_score_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.quality_score_id_seq OWNED BY public.quality_score.id;
ALTER TABLE ONLY public.asset_classification ALTER COLUMN id SET DEFAULT nextval('public.asset_classification_id_seq'::regclass);
ALTER TABLE ONLY public.lineage_record ALTER COLUMN id SET DEFAULT nextval('public.lineage_record_id_seq'::regclass);
ALTER TABLE ONLY public.quality_check_batch ALTER COLUMN id SET DEFAULT nextval('public.quality_check_batch_id_seq'::regclass);
ALTER TABLE ONLY public.quality_check_detail ALTER COLUMN id SET DEFAULT nextval('public.quality_check_detail_id_seq'::regclass);
ALTER TABLE ONLY public.quality_job ALTER COLUMN id SET DEFAULT nextval('public.quality_job_id_seq'::regclass);
ALTER TABLE ONLY public.quality_job_rule ALTER COLUMN id SET DEFAULT nextval('public.quality_job_rule_id_seq'::regclass);
ALTER TABLE ONLY public.quality_rule ALTER COLUMN id SET DEFAULT nextval('public.quality_rule_id_seq'::regclass);
ALTER TABLE ONLY public.quality_rule_template ALTER COLUMN id SET DEFAULT nextval('public.quality_rule_template_id_seq'::regclass);
ALTER TABLE ONLY public.quality_score ALTER COLUMN id SET DEFAULT nextval('public.quality_score_id_seq'::regclass);
ALTER TABLE ONLY public.quality_score_config ALTER COLUMN id SET DEFAULT nextval('public.quality_score_config_id_seq'::regclass);
ALTER TABLE ONLY public.asset_classification
    ADD CONSTRAINT asset_classification_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.collect_change_detail
    ADD CONSTRAINT collect_change_detail_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.collect_execution_log
    ADD CONSTRAINT collect_execution_log_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.collect_history
    ADD CONSTRAINT collect_history_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.collect_task
    ADD CONSTRAINT collect_task_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.compliance_check_result
    ADD CONSTRAINT compliance_check_result_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.field_type_standard
    ADD CONSTRAINT field_type_standard_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.lineage_record
    ADD CONSTRAINT lineage_record_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.metadata_column
    ADD CONSTRAINT metadata_column_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.metadata_table
    ADD CONSTRAINT metadata_table_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.naming_standard
    ADD CONSTRAINT naming_standard_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_check_batch
    ADD CONSTRAINT quality_check_batch_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_check_detail
    ADD CONSTRAINT quality_check_detail_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_job
    ADD CONSTRAINT quality_job_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_job_rule
    ADD CONSTRAINT quality_job_rule_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_rule
    ADD CONSTRAINT quality_rule_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_rule_template
    ADD CONSTRAINT quality_rule_template_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_score_config
    ADD CONSTRAINT quality_score_config_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.quality_score
    ADD CONSTRAINT quality_score_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.asset_classification
    ADD CONSTRAINT uk_asset_classification_level_name UNIQUE (level, name);
ALTER TABLE ONLY public.quality_job
    ADD CONSTRAINT uk_quality_job_name UNIQUE (name);
ALTER TABLE ONLY public.quality_job_rule
    ADD CONSTRAINT uk_quality_job_rule UNIQUE (job_id, rule_id);
ALTER TABLE ONLY public.quality_rule
    ADD CONSTRAINT uk_quality_rule_name UNIQUE (name);
ALTER TABLE ONLY public.quality_rule_template
    ADD CONSTRAINT uk_quality_rule_template_name UNIQUE (name);
ALTER TABLE ONLY public.quality_score
    ADD CONSTRAINT uk_quality_score_table UNIQUE (table_id);
CREATE INDEX idx_asset_classification_level ON public.asset_classification USING btree (level);
CREATE INDEX idx_asset_classification_parent_id ON public.asset_classification USING btree (parent_id);
CREATE INDEX idx_change_detail_history ON public.collect_change_detail USING btree (history_id);
CREATE INDEX idx_collect_execution_log_created_at ON public.collect_execution_log USING btree (created_at);
CREATE INDEX idx_collect_execution_log_history_id ON public.collect_execution_log USING btree (history_id);
CREATE INDEX idx_collect_execution_log_task_id ON public.collect_execution_log USING btree (task_id);
CREATE INDEX idx_collect_history_started_at ON public.collect_history USING btree (started_at);
CREATE INDEX idx_collect_history_status ON public.collect_history USING btree (status);
CREATE INDEX idx_collect_history_task_id ON public.collect_history USING btree (task_id);
CREATE INDEX idx_collect_history_task_id_started_at ON public.collect_history USING btree (task_id, started_at);
CREATE INDEX idx_collect_task_datasource_id ON public.collect_task USING btree (datasource_id);
CREATE INDEX idx_collect_task_status ON public.collect_task USING btree (status);
CREATE INDEX idx_compliance_check_result_checked_at ON public.compliance_check_result USING btree (checked_at);
CREATE INDEX idx_compliance_check_result_column_id ON public.compliance_check_result USING btree (column_id);
CREATE INDEX idx_compliance_check_result_database_name ON public.compliance_check_result USING btree (database_name);
CREATE INDEX idx_compliance_check_result_datasource_id ON public.compliance_check_result USING btree (datasource_id);
CREATE INDEX idx_compliance_check_result_ignored ON public.compliance_check_result USING btree (ignored);
CREATE INDEX idx_compliance_check_result_object_type ON public.compliance_check_result USING btree (object_type);
CREATE INDEX idx_compliance_check_result_schema_name ON public.compliance_check_result USING btree (schema_name);
CREATE INDEX idx_compliance_check_result_standard_id ON public.compliance_check_result USING btree (standard_id);
CREATE INDEX idx_compliance_check_result_table_id ON public.compliance_check_result USING btree (table_id);
CREATE INDEX idx_compliance_check_result_violation_type ON public.compliance_check_result USING btree (violation_type);
CREATE INDEX idx_lineage_dag ON public.lineage_record USING btree (dag_id);
CREATE INDEX idx_lineage_target ON public.lineage_record USING btree (target_table);
CREATE INDEX idx_metadata_column_table_id ON public.metadata_column USING btree (table_id);
CREATE INDEX idx_metadata_table_database_name ON public.metadata_table USING btree (database_name);
CREATE INDEX idx_metadata_table_datasource_id ON public.metadata_table USING btree (datasource_id);
CREATE INDEX idx_naming_standard_applies_to ON public.naming_standard USING btree (applies_to);
CREATE INDEX idx_naming_standard_enabled ON public.naming_standard USING btree (enabled);
CREATE INDEX idx_naming_standard_target_standard_id ON public.naming_standard USING btree (target_standard_id);
CREATE INDEX idx_quality_check_batch_job_id ON public.quality_check_batch USING btree (job_id);
CREATE INDEX idx_quality_check_batch_status ON public.quality_check_batch USING btree (status);
CREATE INDEX idx_quality_check_detail_batch_id ON public.quality_check_detail USING btree (batch_id);
CREATE INDEX idx_quality_check_detail_rule_id ON public.quality_check_detail USING btree (rule_id);
CREATE INDEX idx_quality_job_rule_rule_id ON public.quality_job_rule USING btree (rule_id);
CREATE INDEX idx_quality_rule_table_id ON public.quality_rule USING btree (table_id);
CREATE INDEX idx_quality_rule_template_type ON public.quality_rule_template USING btree (type);
CREATE INDEX idx_quality_score_datasource_id ON public.quality_score USING btree (datasource_id);
CREATE UNIQUE INDEX uk_collect_task_name ON public.collect_task USING btree (name);
CREATE UNIQUE INDEX uk_field_type_standard_name ON public.field_type_standard USING btree (name);
CREATE UNIQUE INDEX uk_metadata_column_unique ON public.metadata_column USING btree (table_id, column_name);
CREATE UNIQUE INDEX uk_metadata_table_unique ON public.metadata_table USING btree (datasource_id, database_name, COALESCE(schema_name, ''::character varying), table_name);
CREATE UNIQUE INDEX uk_naming_standard_name ON public.naming_standard USING btree (name);
