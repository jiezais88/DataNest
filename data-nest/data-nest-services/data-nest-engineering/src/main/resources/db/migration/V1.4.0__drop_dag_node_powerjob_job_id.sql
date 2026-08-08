-- ============================================
-- V1.4.0__drop_dag_node_powerjob_job_id.sql
-- 5 内置共享 job 重构：DAG 节点不再单独注册 PowerJob job（改为 5 个内置共享 job + 节点身份 JSON 写 workflow 节点 nodeParams），
-- dag_node.powerjob_job_id 列随之废弃删除；powerjob_node_id 列保留（workflow_node_info 节点 ID，状态同步在用）。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

ALTER TABLE dag_node DROP COLUMN IF EXISTS powerjob_job_id;
