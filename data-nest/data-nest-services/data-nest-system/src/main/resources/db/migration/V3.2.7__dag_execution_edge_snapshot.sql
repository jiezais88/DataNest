-- ============================================
-- V3.2.7__dag_execution_edge_snapshot.sql
-- dag_execution 加 edge_snapshot：
-- trigger 创建执行实例时把当时的 dag_edge 列表序列化为 JSON 快照
-- 存入该列，DAG 历史视图（run-view）用快照渲染边，
-- 避免用户删除节点后历史实例的连线丢失
-- ============================================

ALTER TABLE dag_execution
    ADD COLUMN IF NOT EXISTS edge_snapshot TEXT;

COMMENT ON COLUMN dag_execution.edge_snapshot IS '创建执行实例时的 dag_edge JSON 快照（[{"source":"<nodeId>","target":"<nodeId>"},...]，无边时为 []，历史视图渲染边用）';
