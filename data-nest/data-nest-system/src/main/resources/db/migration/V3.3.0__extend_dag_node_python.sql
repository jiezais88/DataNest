-- ============================================
-- V3.3.0__extend_dag_node_python.sql
-- Sprint 4：DAG 节点类型扩展为 SQL / SYNC / PYTHON
-- ============================================

-- 当前 dag_node.node_type / node_execution.node_type 均无 CHECK 约束，
-- 仅通过注释说明类型含义。本次更新注释，并在需要时补上 CHECK 约束。
COMMENT
ON COLUMN dag_node.node_type IS '节点类型：SQL SQL 任务，SYNC 同步任务，PYTHON Python 任务';
COMMENT
ON COLUMN node_execution.node_type IS '节点类型：SQL / SYNC / PYTHON';

-- 若已存在旧 CHECK 约束则先删除（幂等）
ALTER TABLE dag_node
DROP
CONSTRAINT IF EXISTS chk_dag_node_node_type;

-- 限制允许的节点类型
ALTER TABLE dag_node
    ADD CONSTRAINT chk_dag_node_node_type
        CHECK (node_type IN ('SQL', 'SYNC', 'PYTHON'));
