-- ============================================
-- V3.2.5__drop_dead_columns_and_invalid_index.sql
-- 清理死列与无效索引
-- ============================================

-- --------------------------------------------
-- 删除死列 metadata_table.column_count
-- 该列由 V3.0.6 创建，但实体 MetadataTable 上标注 @TableField(exist = false)，
-- MyBatis-Plus 写入路径永远不会写该列，列值恒为 0；
-- 查询端（MetadataTableMapper）全部通过相关子查询实时 COUNT(metadata_column) 计算，
-- 无任何 SQL 直接读取该真实列，故删除。
-- 注意：collect_history.column_count 是另一张表的有效统计列，不在本迁移范围内。
-- --------------------------------------------
ALTER TABLE metadata_table
DROP
COLUMN IF EXISTS column_count;

-- --------------------------------------------
-- 删除死列 sync_job.retry_count / sync_job.next_retry_at
-- 两列由 V3.0.3 创建，V3.0.9 起同步重试状态已迁移到 sync_job_history，
-- 实体 SyncJob 无对应字段，V3.0.9 已执行过 DROP；
-- 此处用 IF EXISTS 幂等兜底，防止个别环境迁移历史缺失导致列残留。
-- 注意：sync_job_history.retry_count / next_retry_at 为有效列，不在本迁移范围内。
-- --------------------------------------------
ALTER TABLE sync_job
DROP
COLUMN IF EXISTS retry_count,
    DROP
COLUMN IF EXISTS next_retry_at;

-- --------------------------------------------
-- 删除无效索引 idx_dag_node_config_pattern
-- V3.2.0 对 dag_node.config 建的 text_pattern_ops 部分索引，
-- 其谓词是前导通配符 LIKE '%syncJobId%'，text_pattern_ops 无法支持前导通配符匹配，
-- 且实际查询已改用正则 ~ 运算符，该索引永远不会被命中，故删除。
-- --------------------------------------------
DROP INDEX IF EXISTS idx_dag_node_config_pattern;
