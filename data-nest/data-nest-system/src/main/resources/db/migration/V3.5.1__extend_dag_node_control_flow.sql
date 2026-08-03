-- ============================================
-- V3.5.1__extend_dag_node_control_flow.sql
-- Sprint 5：DAG 节点类型扩展为 SQL / SYNC / PYTHON / CONDITION / SUB_DAG
-- ============================================

COMMENT
ON COLUMN dag_node.node_type IS '节点类型：SQL SQL任务，SYNC 同步任务，PYTHON Python任务，CONDITION 条件分支，SUB_DAG 子DAG';
COMMENT
ON COLUMN node_execution.node_type IS '节点类型：SQL / SYNC / PYTHON / CONDITION / SUB_DAG';

-- 重建 dag_node.node_type CHECK 约束（node_execution 无 CHECK，仅注释）
ALTER TABLE dag_node
DROP
CONSTRAINT IF EXISTS chk_dag_node_node_type;

ALTER TABLE dag_node
    ADD CONSTRAINT chk_dag_node_node_type
        CHECK (node_type IN ('SQL', 'SYNC', 'PYTHON', 'CONDITION', 'SUB_DAG'));
