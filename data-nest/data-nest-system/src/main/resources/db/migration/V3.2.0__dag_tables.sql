-- ============================================
-- V3.2.0__dag_tables.sql
-- Sprint 3：6 张 DAG 领域表
-- 表: dag_project, dag, dag_node, dag_edge, dag_execution, node_execution
-- 决策：dag_node.config 用 TEXT 存 JSON（ADR-S3-005），不用 JacksonTypeHandler
-- ============================================

-- --------------------------------------------
-- 1. dag_project — DAG 命名空间（项目维度）
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS dag_project (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dag_project_name ON dag_project (name);

COMMENT ON TABLE dag_project IS 'DAG 项目（DAG 命名空间，全局唯一）';
COMMENT ON COLUMN dag_project.id IS '主键ID';
COMMENT ON COLUMN dag_project.name IS '项目名（全局唯一）';
COMMENT ON COLUMN dag_project.description IS '项目描述';
COMMENT ON COLUMN dag_project.created_by IS '创建人ID';
COMMENT ON COLUMN dag_project.updated_by IS '更新人ID';
COMMENT ON COLUMN dag_project.created_at IS '创建时间';
COMMENT ON COLUMN dag_project.updated_at IS '更新时间';

-- --------------------------------------------
-- 2. dag — DAG 定义
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS dag (
    id BIGINT NOT NULL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    cron_expression VARCHAR(100) DEFAULT NULL,
    schedule_enabled SMALLINT NOT NULL DEFAULT 0,
    max_parallelism INT NOT NULL DEFAULT 3,
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    ds_project_code BIGINT DEFAULT NULL,
    ds_process_definition_id BIGINT DEFAULT NULL,
    ds_process_definition_code BIGINT DEFAULT NULL,
    ds_schedule_id BIGINT DEFAULT NULL,
    release_state VARCHAR(20) DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dag_project_id_name ON dag (project_id, name);
CREATE INDEX IF NOT EXISTS idx_dag_project_id ON dag (project_id);
CREATE INDEX IF NOT EXISTS idx_dag_status ON dag (status);
CREATE INDEX IF NOT EXISTS idx_dag_ds_process_definition ON dag (ds_process_definition_code);

COMMENT ON TABLE dag IS 'DAG 定义（一个 DAG 同步为 DS 一个 ProcessDefinition）';
COMMENT ON COLUMN dag.id IS '主键ID';
COMMENT ON COLUMN dag.project_id IS '所属项目ID（关联 dag_project.id）';
COMMENT ON COLUMN dag.name IS 'DAG 名（项目内唯一）';
COMMENT ON COLUMN dag.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN dag.cron_expression IS 'Cron 表达式（trigger_type=CRON 时必填）';
COMMENT ON COLUMN dag.schedule_enabled IS '调度是否启用：0-停止 1-运行';
COMMENT ON COLUMN dag.max_parallelism IS '最大并行节点数（默认 3）';
COMMENT ON COLUMN dag.status IS 'DAG 状态：ENABLED 启用，DISABLED 禁用';
COMMENT ON COLUMN dag.ds_project_code IS 'DS 项目 Code（关联 DolphinScheduler t_ds_project.code）';
COMMENT ON COLUMN dag.ds_process_definition_id IS 'DS 流程定义 ID';
COMMENT ON COLUMN dag.ds_process_definition_code IS 'DS 流程定义 Code（用于调用 DS API）';
COMMENT ON COLUMN dag.ds_schedule_id IS 'DS 调度 ID（CRON 触发时产生）';
COMMENT ON COLUMN dag.release_state IS 'DS 发布状态：OFFLINE / ONLINE';
COMMENT ON COLUMN dag.created_by IS '创建人ID';
COMMENT ON COLUMN dag.updated_by IS '更新人ID';
COMMENT ON COLUMN dag.created_at IS '创建时间';
COMMENT ON COLUMN dag.updated_at IS '更新时间';

-- --------------------------------------------
-- 3. dag_node — DAG 节点（SQL / SYNC 类型）
-- config 用 TEXT 存 JSON（决策 ADR-S3-005：String，非 JacksonTypeHandler）
-- JSON 形态：{"type":"SQL","sqlContent":"SELECT ..."} 或 {"type":"SYNC","syncJobId":123,"syncJobName":"xxx"}
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS dag_node (
    id BIGINT NOT NULL PRIMARY KEY,
    dag_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(100) NOT NULL,
    node_type VARCHAR(20) NOT NULL,
    position_x DOUBLE PRECISION DEFAULT 0,
    position_y DOUBLE PRECISION DEFAULT 0,
    config TEXT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dag_node_dag_id_node_id ON dag_node (dag_id, node_id);
CREATE INDEX IF NOT EXISTS idx_dag_node_dag_id ON dag_node (dag_id);
CREATE INDEX IF NOT EXISTS idx_dag_node_node_type ON dag_node (node_type);
-- 同步任务引用校验索引：dag_node.config LIKE '%syncJobId":xxx%' 场景（ADR-S3-009）
CREATE INDEX IF NOT EXISTS idx_dag_node_config_pattern ON dag_node (config text_pattern_ops)
    WHERE config LIKE '%syncJobId%';

COMMENT ON TABLE dag_node IS 'DAG 节点（SQL 任务 / SYNC 任务）';
COMMENT ON COLUMN dag_node.id IS '主键ID';
COMMENT ON COLUMN dag_node.dag_id IS '所属 DAG ID（关联 dag.id）';
COMMENT ON COLUMN dag_node.node_id IS '节点 ID（DAG 内唯一，前端生成的 UUID）';
COMMENT ON COLUMN dag_node.node_name IS '节点名称（用户可读）';
COMMENT ON COLUMN dag_node.node_type IS '节点类型：SQL SQL 任务，SYNC 同步任务';
COMMENT ON COLUMN dag_node.position_x IS '画布 X 坐标';
COMMENT ON COLUMN dag_node.position_y IS '画布 Y 坐标';
COMMENT ON COLUMN dag_node.config IS '节点配置 JSON 字符串（String 存 JSON，决策 ADR-S3-005）。SQL: {type:SQL,sqlContent:str}；SYNC: {type:SYNC,syncJobId:num,syncJobName:str}';
COMMENT ON COLUMN dag_node.created_by IS '创建人ID';
COMMENT ON COLUMN dag_node.updated_by IS '更新人ID';
COMMENT ON COLUMN dag_node.created_at IS '创建时间';
COMMENT ON COLUMN dag_node.updated_at IS '更新时间';

-- --------------------------------------------
-- 4. dag_edge — DAG 边（节点依赖关系）
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS dag_edge (
    id BIGINT NOT NULL PRIMARY KEY,
    dag_id BIGINT NOT NULL,
    edge_id VARCHAR(64) NOT NULL,
    source_node_id VARCHAR(64) NOT NULL,
    target_node_id VARCHAR(64) NOT NULL,
    created_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dag_edge_dag_id_edge_id ON dag_edge (dag_id, edge_id);
CREATE INDEX IF NOT EXISTS idx_dag_edge_dag_id ON dag_edge (dag_id);
CREATE INDEX IF NOT EXISTS idx_dag_edge_source_node_id ON dag_edge (source_node_id);
CREATE INDEX IF NOT EXISTS idx_dag_edge_target_node_id ON dag_edge (target_node_id);

COMMENT ON TABLE dag_edge IS 'DAG 边（节点依赖关系，source → target）';
COMMENT ON COLUMN dag_edge.id IS '主键ID';
COMMENT ON COLUMN dag_edge.dag_id IS '所属 DAG ID（关联 dag.id）';
COMMENT ON COLUMN dag_edge.edge_id IS '边 ID（DAG 内唯一）';
COMMENT ON COLUMN dag_edge.source_node_id IS '源节点 node_id';
COMMENT ON COLUMN dag_edge.target_node_id IS '目标节点 node_id';
COMMENT ON COLUMN dag_edge.created_by IS '创建人ID';
COMMENT ON COLUMN dag_edge.created_at IS '创建时间';

-- --------------------------------------------
-- 5. dag_execution — DAG 执行实例
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS dag_execution (
    id BIGINT NOT NULL PRIMARY KEY,
    dag_id BIGINT NOT NULL,
    ds_process_instance_id BIGINT DEFAULT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    start_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMP DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dag_execution_dag_id ON dag_execution (dag_id);
CREATE INDEX IF NOT EXISTS idx_dag_execution_ds_process_instance_id ON dag_execution (ds_process_instance_id);
CREATE INDEX IF NOT EXISTS idx_dag_execution_status ON dag_execution (status);
CREATE INDEX IF NOT EXISTS idx_dag_execution_start_time ON dag_execution (start_time);

COMMENT ON TABLE dag_execution IS 'DAG 执行实例（一次 DAG 触发对应一条记录）';
COMMENT ON COLUMN dag_execution.id IS '主键ID';
COMMENT ON COLUMN dag_execution.dag_id IS '所属 DAG ID（关联 dag.id）';
COMMENT ON COLUMN dag_execution.ds_process_instance_id IS 'DS 流程实例 ID（用于查询 DS 状态）';
COMMENT ON COLUMN dag_execution.trigger_type IS '触发方式：MANUAL 手动，CRON 定时';
COMMENT ON COLUMN dag_execution.status IS '执行状态：RUNNING 运行中，SUCCESS 成功，FAILED 失败，TERMINATED 终止';
COMMENT ON COLUMN dag_execution.start_time IS '开始时间';
COMMENT ON COLUMN dag_execution.end_time IS '结束时间';
COMMENT ON COLUMN dag_execution.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN dag_execution.created_by IS '创建人ID';
COMMENT ON COLUMN dag_execution.created_at IS '创建时间';

-- --------------------------------------------
-- 6. node_execution — 节点执行实例
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS node_execution (
    id BIGINT NOT NULL PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(100) NOT NULL,
    node_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    ds_task_instance_id BIGINT DEFAULT NULL,
    start_time TIMESTAMP DEFAULT NULL,
    end_time TIMESTAMP DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    error_message TEXT DEFAULT NULL,
    output_info TEXT DEFAULT NULL
);

CREATE INDEX IF NOT EXISTS idx_node_execution_execution_id ON node_execution (execution_id);
CREATE INDEX IF NOT EXISTS idx_node_execution_ds_task_instance_id ON node_execution (ds_task_instance_id);
CREATE INDEX IF NOT EXISTS idx_node_execution_status ON node_execution (status);

COMMENT ON TABLE node_execution IS '节点执行实例（DAG 内每个节点一条）';
COMMENT ON COLUMN node_execution.id IS '主键ID';
COMMENT ON COLUMN node_execution.execution_id IS '所属 DAG 执行实例 ID（关联 dag_execution.id）';
COMMENT ON COLUMN node_execution.node_id IS '节点 node_id（关联 dag_node.node_id）';
COMMENT ON COLUMN node_execution.node_name IS '节点名称（冗余存储，便于历史快照）';
COMMENT ON COLUMN node_execution.node_type IS '节点类型：SQL / SYNC';
COMMENT ON COLUMN node_execution.status IS '执行状态：WAITING 等待，RUNNING 运行中，SUCCESS 成功，FAILED 失败，SKIPPED 跳过';
COMMENT ON COLUMN node_execution.ds_task_instance_id IS 'DS 任务实例 ID';
COMMENT ON COLUMN node_execution.start_time IS '开始时间';
COMMENT ON COLUMN node_execution.end_time IS '结束时间';
COMMENT ON COLUMN node_execution.duration_ms IS '执行耗时（毫秒）';
COMMENT ON COLUMN node_execution.error_message IS '错误信息';
COMMENT ON COLUMN node_execution.output_info IS '输出信息（影响行数、创建的表名等）';
