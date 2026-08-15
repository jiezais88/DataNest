-- ============================================
-- V1.7.1__sprint11_queue_waiting_uk.sql
-- Sprint 11 F3 P0-1 修复：排队去重唯一索引。
--   uk_dag_execution_waiting：同 DAG 最多一条 WAITING（status='WAITING' 部分唯一索引），
--   与既有 uk_dag_execution_running（RUNNING 部分唯一索引）共同保证「一个 DAG 同时至多一个
--   运行中/排队中的执行实例」，消除 trigger 前置 selectCount 检查的 TOCTOU 竞态。
-- 注意：紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================
CREATE UNIQUE INDEX IF NOT EXISTS uk_dag_execution_waiting ON public.dag_execution USING btree (dag_id) WHERE (status = 'WAITING');
