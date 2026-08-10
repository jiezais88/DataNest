-- ============================================
-- V1.4.1__sprint8_asset_favorite_table_index.sql
-- Sprint 8 F1 评审修复：asset_favorite 补 table_id 索引
-- （uk 是 (user_id, table_id) 不覆盖 table_id 前缀，表删除级联清理按 table_id 删会全表扫描）
-- 注意：紧凑单行风格，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

CREATE INDEX IF NOT EXISTS idx_asset_favorite_table ON asset_favorite (table_id);
