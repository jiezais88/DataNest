-- ============================================
-- V1.2.0__dag_node_powerjob_node_id.sql
-- P3 收口：dag_node 记录 PowerJob 工作流节点记录 ID（workflow_node_info 表主键）
-- 说明：saveWorkflow 的 PJDag Node.nodeId 必须是 server 侧已注册的节点记录 ID，
--       持久化到 dag_node 以支持按 id 幂等更新（避免重复注册产生 server 侧残留）；
--       只加不改，新列可空、无默认值，对存量数据零影响（存量节点下次同步时新建并回写）。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

ALTER TABLE dag_node ADD COLUMN IF NOT EXISTS powerjob_node_id BIGINT;

COMMENT ON COLUMN dag_node.powerjob_node_id IS 'PowerJob workflow_node_info 节点 ID';
